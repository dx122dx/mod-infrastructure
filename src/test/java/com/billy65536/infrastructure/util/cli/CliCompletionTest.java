package com.billy65536.infrastructure.util.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.billy65536.infrastructure.util.reflect.FlatConfigs;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliCompletion 补全建议测试。
 *
 * <p>覆盖两类关注点：</p>
 * <ol>
 *   <li><b>分段部分补全</b>（参考 PartialIdAutocomplete）：同时给出「与输入段数对应的累积前缀」
 *       与完整键，单子节点自动折叠到分叉层，避免选中中间层却没有前进的空转；</li>
 *   <li><b>偏移越界崩溃回归</b>：玩家敲空格触发补全时，SmartCompletion 等模组会拦截空格插入，
 *       使「解析期输入串」比「应用期文本」长 1 个尾随空白，
 *       {@link Suggestion#apply(String)} 的 {@code input.substring(0, range.getStart())} 因此越界。</li>
 * </ol>
 *
 * <p>测试直接驱动生产代码 {@link CliCompletion#hierarchicalProvide} /
 * {@link CliCompletion#positionalProvide}（命令上下文传 {@code null}，测试用的 source 不读取它）。</p>
 */
@DisplayName("CliCompletion 补全建议")
class CliCompletionTest {

    /** 模拟 SmartCompletion：把触发补全的尾随空格从应用期文本中移除。 */
    private static String dropTrailingWs(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    private static Suggestions buildHierarchical(String input, int start, CliCompletion.Builder b) {
        return CliCompletion.hierarchicalProvide(new SuggestionsBuilder(input, start), null, b).join();
    }

    private static Suggestions buildPositional(String input, int start, CliCompletion.Builder b) {
        return CliCompletion.positionalProvide(new SuggestionsBuilder(input, start), null, b).join();
    }

    /** 注意：Brigadier 会对建议按文本排序，故断言集合内容而非插入顺序。 */
    private static List<String> textsOf(Suggestions s) {
        return s.getList().stream().map(Suggestion::getText).toList();
    }

    private static void assertSameItems(List<String> expected, List<String> actual) {
        assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList());
    }

    @Nested
    @DisplayName("分段部分补全")
    class PartialSegments {

        private static final String PREFIX = "/inf dbg action run ";

        private CliCompletion.Builder keys() {
            return CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of(
                            "inf-dbg:security.config-locker.set-authorized",
                            "inf-dbg:security.config-locker.clear",
                            "infrastructure:config"));
        }

        @Test
        @DisplayName("单子节点折叠：直接给出分叉那一级，而非逐段空转")
        void collapsesSingleChildChain() {
            String input = PREFIX;
            Suggestions s = buildHierarchical(input, input.length(), keys());
            List<String> texts = textsOf(s);

            assertTrue(texts.contains("inf-dbg:security.config-locker."),
                    "inf-dbg 命名空间下唯一链路应折叠到分叉层，实际：" + texts);
            assertFalse(texts.contains("inf-dbg:"), "中间单子节点不应单独建议：" + texts);
            assertFalse(texts.contains("inf-dbg:security."), "中间单子节点不应单独建议：" + texts);
            assertFalse(texts.contains("infrastructure:"),
                    "只被一个完整键覆盖的部分 id 应隐藏（完整键本身已在建议中）：" + texts);
            assertTrue(texts.contains("infrastructure:config"), texts.toString());
        }

        @Test
        @DisplayName("部分 id 与完整键同时给出，可逐级钻取也可一次选完")
        void offersBothPartialAndFullKeys() {
            String input = PREFIX + "inf-dbg:security.";
            Suggestions s = buildHierarchical(input, PREFIX.length(), keys());
            List<String> texts = textsOf(s);

            assertTrue(texts.contains("inf-dbg:security.config-locker."), texts.toString());
            assertTrue(texts.contains("inf-dbg:security.config-locker.set-authorized"), texts.toString());
            assertTrue(texts.contains("inf-dbg:security.config-locker.clear"), texts.toString());
            for (Suggestion sug : s.getList()) {
                assertEquals(PREFIX + sug.getText(), sug.apply(input), "建议应整体替换当前条目");
            }
        }

        @Test
        @DisplayName("已到叶层：只给完整键，不再产出部分 id")
        void noPartialAtLeafLevel() {
            String input = PREFIX + "inf-dbg:security.config-locker.";
            Suggestions s = buildHierarchical(input, PREFIX.length(), keys());
            assertSameItems(List.of(
                    "inf-dbg:security.config-locker.set-authorized",
                    "inf-dbg:security.config-locker.clear"), textsOf(s));
        }

        @Test
        @DisplayName("部分 id 恒长于当前输入，保证每次补全都有进展")
        void partialIdAlwaysAdvances() {
            for (String typed : List.of("", "inf", "inf-dbg:", "inf-dbg:sec", "inf-dbg:security.")) {
                String input = PREFIX + typed;
                Suggestions s = buildHierarchical(input, PREFIX.length(), keys());
                for (String text : textsOf(s)) {
                    assertTrue(text.length() > typed.length(),
                            "输入 '" + typed + "' 的建议 '" + text + "' 未前进");
                }
            }
        }
    }

    @Nested
    @DisplayName("模糊回退（前缀无命中时按子串匹配）")
    class FuzzyFallback {

        private static final String PREFIX = "/inf dbg action run ";

        private CliCompletion.Builder keys() {
            return CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of(
                            "inf-dbg:security.config-locker.set-authorized",
                            "inf-dbg:security.config-locker.clear",
                            "infrastructure:config"));
        }

        @Test
        @DisplayName("只输入末段即可命中完整键")
        void tailFragmentMatchesFullKey() {
            String input = PREFIX + "set-auth";
            Suggestions s = buildHierarchical(input, PREFIX.length(), keys());
            assertEquals(List.of("inf-dbg:security.config-locker.set-authorized"), textsOf(s));
            for (Suggestion sug : s.getList()) {
                assertEquals(PREFIX + sug.getText(), sug.apply(input), "回退命中也应整体替换当前条目");
            }
        }

        @Test
        @DisplayName("中间片段可命中多个键，不含该子串的键被排除")
        void middleFragmentMatchesMultiple() {
            String input = PREFIX + "config-locker";
            List<String> texts = textsOf(buildHierarchical(input, PREFIX.length(), keys()));
            assertTrue(texts.contains("inf-dbg:security.config-locker.set-authorized"), texts.toString());
            assertTrue(texts.contains("inf-dbg:security.config-locker.clear"), texts.toString());
            assertFalse(texts.contains("infrastructure:config"), texts.toString());
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            String input = PREFIX + "SET-AUTH";
            List<String> texts = textsOf(buildHierarchical(input, PREFIX.length(), keys()));
            assertTrue(texts.contains("inf-dbg:security.config-locker.set-authorized"), texts.toString());
        }
    }

    @Nested
    @DisplayName("尾随空格越界崩溃回归（SmartCompletion 移除尾随空格）")
    class TrailingSpaceCrash {

        @Test
        @DisplayName("建议可安全应用于缺少尾随空格的文本，且正确追加带前导空格的条目")
        void safeAgainstShortText() {
            String input = "/inf dbg action run inf-dbg:security.config-locker.set-authorized ";
            int start = "/inf dbg action run ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of(
                            "infrastructure:config", "chunkscanner:config",
                            "inf-dbg:security.config-locker.set-authorized"));

            Suggestions s = buildHierarchical(input, start, b);
            String applyText = dropTrailingWs(input); // SmartCompletion 实际传入 apply 的文本
            assertFalse(s.getList().isEmpty(), "追加新条目时应给出候选");
            for (Suggestion sug : s.getList()) {
                String applied = assertDoesNotThrow(() -> sug.apply(applyText),
                        "建议应用于缺尾随空格的文本时不应越界");
                assertEquals(applyText + sug.getText(), applied, "补全结果应为应用期文本拼接补全文本");
                assertTrue(sug.getText().startsWith(" "), "追加的新条目补全文本应带前导空格");
            }
        }

        @Test
        @DisplayName("无尾随空格：正常补全当前片段不崩溃")
        void noTrailingSpaceIsFine() {
            String input = "/inf dbg action run inf-dbg:security.config-locker.";
            int start = "/inf dbg action run ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of("inf-dbg:security.config-locker.set-authorized"));

            for (Suggestion sug : buildHierarchical(input, start, b).getList()) {
                assertDoesNotThrow(() -> sug.apply(input));
            }
        }

        @Test
        @DisplayName("末尾分隔符：逐层钻取偏移合法")
        void trailingSeparatorIsSafe() {
            String input = "/inf config set infrastructure:config/";
            int start = "/inf config set ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .keySource(ctx -> List.of("infrastructure:config/someOption"));

            String applyText = dropTrailingWs(input);
            for (Suggestion sug : buildHierarchical(input, start, b).getList()) {
                assertDoesNotThrow(() -> sug.apply(applyText));
            }
        }
    }

    @Nested
    @DisplayName("assignment 取值补全")
    class Assignment {

        private CliCompletion.Builder builder() {
            return CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .assignment(true)
                    .keySource(ctx -> List.of("infrastructure:config/foo"))
                    .valueProvider((ctx, key) -> List.of("true", "false"));
        }

        @Test
        @DisplayName("含 '=' 时补全取值，不崩溃")
        void suggestsValuesAfterEquals() {
            String input = "/inf config set infrastructure:config/foo=";
            int start = "/inf config set ".length();

            Suggestions s = buildHierarchical(input, start, builder());
            assertSameItems(List.of("true", "false"), textsOf(s));
            for (Suggestion sug : s.getList()) {
                assertDoesNotThrow(() -> sug.apply(dropTrailingWs(input)));
            }
        }

        @Test
        @DisplayName("完整键追加 '=' 及取值，部分 id 不追加")
        void appendsEqualsOnlyToFullKeys() {
            String input = "/inf config set ";
            List<String> texts = textsOf(buildHierarchical(input, input.length(), builder()));
            assertSameItems(List.of(
                    "infrastructure:config/foo=",
                    "infrastructure:config/foo=true",
                    "infrastructure:config/foo=false"), texts);
        }
    }

    @Nested
    @DisplayName("位置模式")
    class Positional {

        @Test
        @DisplayName("末尾空格追加新参数且不崩溃")
        void trailingSpaceAppends() {
            String input = "/mycmd first ";
            int start = "/mycmd ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .multiple(true)
                    .positional((ctx, completed) -> List.of("alpha", "beta"));

            Suggestions s = buildPositional(input, start, b);
            String applyText = dropTrailingWs(input);
            assertEquals(2, s.getList().size());
            for (Suggestion sug : s.getList()) {
                String applied = assertDoesNotThrow(() -> sug.apply(applyText));
                assertEquals(applyText + sug.getText(), applied);
                assertTrue(sug.getText().startsWith(" "));
            }
        }

        @Test
        @DisplayName("前缀无命中时回退子串匹配")
        void fuzzyFallback() {
            String input = "/mycmd al";
            int start = "/mycmd ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .positional((ctx, completed) -> List.of("global", "beta"));

            assertEquals(List.of("global"), textsOf(buildPositional(input, start, b)));
        }
    }

    @Nested
    @DisplayName("forFlatConfig 快捷补全（FlatConfigs + CliCompletion）")
    class FlatConfigShortcut {

        /** 带 {@link FlatConfigs.Key} 注解的扁平配置类，用于验证键名自动成为补全候选。 */
        static class SampleConfig {
            @FlatConfigs.Key("interval") public Integer interval;
            @FlatConfigs.Key("name")    public String  name;
        }

        private static List<String> suggestKeys(String input) throws CommandSyntaxException {
            var p = CliCompletion.forFlatConfig(SampleConfig.class);
            Suggestions s = p.getSuggestions(null, new SuggestionsBuilder(input, 0)).join();
            return textsOf(s);
        }

        @Test
        @DisplayName("配置类注解键自动成为补全候选，并带 '=' 提示赋值")
        void suggestsAnnotatedKeys() throws Exception {
            assertSameItems(List.of("interval=", "name="), suggestKeys(""));
        }

        @Test
        @DisplayName("valueProvider 在 'key=' 后补全取值")
        void suggestsValuesAfterEquals() throws Exception {
            var p = CliCompletion.forFlatConfig(SampleConfig.class,
                    (ctx, key) -> key.equals("interval") ? List.of("60") : List.of());
            Suggestions s = p.getSuggestions(null, new SuggestionsBuilder("interval=", 0)).join();
            assertSameItems(List.of("60"), textsOf(s));
        }

        @Test
        @DisplayName("无 valueProvider 时取值为空，不越界崩溃")
        void noValueProviderIsSafe() throws Exception {
            Suggestions s = CliCompletion.forFlatConfig(SampleConfig.class)
                    .getSuggestions(null, new SuggestionsBuilder("interval=", 0))
                    .join();
            assertTrue(s.getList().isEmpty(), "未提供取值候选时不应给出任何建议：" + textsOf(s));
        }
    }
}

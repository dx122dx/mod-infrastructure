package com.billy65536.infrastructure.core.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import com.billy65536.infrastructure.core.reflect.FlatConfigs;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 层级化命令补全工具，实现「分段部分补全」（partial id autocomplete）。
 *
 * <p>参考 <a href="https://github.com/Papierkorb2292/PartialIdAutocomplete">PartialIdAutocomplete</a>
 * 的补全模型：候选键按分隔符（{@code . : /}）切成若干「段」，补全时<b>同时</b>给出
 * <ul>
 *   <li><b>部分 id</b>：与当前输入段数对应的那一级累积前缀（分隔符保留在段尾，如
 *       {@code inf-dbg:security.config-locker.}），玩家可逐级钻取长路径；</li>
 *   <li><b>完整键</b>：可一次性选中整条路径。</li>
 * </ul>
 *
 * <p><b>单子节点折叠</b>：某一级若只有唯一后继则不单独建议，直接下钻到出现分叉的那一级；
 * 若某部分 id 只被一个完整键覆盖则完全隐藏（该完整键本身已在建议中），避免「选了中间层
 * 却没有前进」的空转。</p>
 *
 * <p><b>模糊回退</b>：前缀匹配无任何结果时回退为子串匹配（如输入 {@code set-auth} 可命中
 * {@code inf-dbg:security.config-locker.set-authorized}），避免长层级键必须从头逐字输入。
 * 该回退是本实现的增强，参考实现不含此行为。</p>
 *
 * <p><b>偏移安全</b>：建议区间经 {@link #offsetOf} 钳制到合法范围；当 {@code remaining} 末尾含空白
 * （触发补全的空格，可能被 SmartCompletion 等模组从应用期文本中移除）时，把当前片段视为空、
 * 偏移锚定到尾随空白之前、补全文本前补一个空格，杜绝 {@code StringIndexOutOfBoundsException}。</p>
 */
public final class CliCompletion {

    private CliCompletion() {}

    /** 候选键默认分隔符集合：点号、冒号、斜杠。 */
    public static final String DEFAULT_SEPARATORS = ".:/";

    // ==================== 构造器 ====================

    /** 补全构造器。通过 {@link #build()} 产出 {@link SuggestionProvider}。 */
    public static final class Builder {
        private String separators = DEFAULT_SEPARATORS;
        private boolean assignment = false;
        private boolean multiple = false;

        private Function<CommandContext<FabricClientCommandSource>, Collection<String>> keySource = null;
        private BiFunction<CommandContext<FabricClientCommandSource>, String, List<String>> valueProvider = null;
        private BiFunction<CommandContext<FabricClientCommandSource>, String[], List<String>> nextProvider = null;

        private Builder() {}

        public Builder separators(String s) { this.separators = (s == null) ? "" : s; return this; }
        public Builder assignment(boolean b) { this.assignment = b; return this; }
        public Builder multiple(boolean b) { this.multiple = b; return this; }

        /** 层级模式：依据命令上下文动态取得候选键集合（可反映运行时变化）。 */
        public Builder keySource(Function<CommandContext<FabricClientCommandSource>, Collection<String>> f) {
            this.keySource = f; return this;
        }

        /** 层级模式 assignment=true 时，依据完整键取得其合法取值候选。 */
        public Builder valueProvider(BiFunction<CommandContext<FabricClientCommandSource>, String, List<String>> f) {
            this.valueProvider = f; return this;
        }

        /** 位置模式：依据已完成的参数片段数组，返回下一个参数的候选。 */
        public Builder positional(BiFunction<CommandContext<FabricClientCommandSource>, String[], List<String>> f) {
            this.nextProvider = f; return this;
        }

        public SuggestionProvider<FabricClientCommandSource> build() {
            if (nextProvider != null) return (ctx, builder) -> positionalProvide(builder, ctx, this);
            if (keySource == null) {
                throw new IllegalStateException("CliCompletion.Builder: 需提供 keySource（层级模式）或 positional（位置模式）");
            }
            return (ctx, builder) -> hierarchicalProvide(builder, ctx, this);
        }
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 由 {@link FlatConfigs} 注解的扁平配置类快捷生成命令补全器（key=value 多段形式）。
     *
     * <p>等价于手工写：</p>
     * <pre>{@code
     * CliCompletion.builder()
     *     .separators("")                          // 扁平键无 .:/ 层级
     *     .assignment(true)                        // 选中键后补 '=' 并提示取值
     *     .multiple(true)                          // 多个 key=value 以空格分隔
     *     .keySource(ctx -> FlatConfigs.keysOf(configClass))
     *     .build();
     * }</pre>
     *
     * <p>候选键直接取自配置类上 {@link FlatConfigs.Key} 声明的别名，与
     * {@link FlatConfigs#createFrom} 解析器物理同源：新增字段只需加注解即全自动补全，
     * 杜绝命令里键名拼写漂移（如 {@code rivist} 与 {@code revisit} 不一致）。</p>
     *
     * @param configClass 带 {@link FlatConfigs.Key} 注解的扁平配置类
     */
    public static SuggestionProvider<FabricClientCommandSource> forFlatConfig(Class<?> configClass) {
        return forFlatConfig(configClass, null);
    }

    /**
     * 同上，并允许为各键提供取值候选：assignment 模式输入 {@code key=} 后触发，
     * 由 {@code valueProvider} 按完整键返回其合法取值（如当前默认值、枚举项）。
     * 返回 {@code null}/空表表示该键为自由文本、不提示取值。
     *
     * <p>{@code valueProvider} 形如 {@code (ctx, key) -> List.of(...)}；取值与命令上下文无关时，
     * 第 2 参数用 {@code (ctx, key) -> f(key)} 包一层即可。</p>
     */
    public static SuggestionProvider<FabricClientCommandSource> forFlatConfig(
            Class<?> configClass,
            BiFunction<CommandContext<FabricClientCommandSource>, String, List<String>> valueProvider) {
        List<String> keys = FlatConfigs.keysOf(configClass);
        Builder b = builder()
                .separators("")
                .assignment(true)
                .multiple(true)
                .keySource(ctx -> keys);
        if (valueProvider != null) b.valueProvider(valueProvider);
        return b.build();
    }

    // ==================== 当前条目的切分结果 ====================

    /** 从 {@code builder.getRemaining()} 解析出的「当前正在输入的条目」及其定位信息。 */
    private static final class Token {
        /** 当前条目文本（末尾空白时为空串）。 */
        final String text;
        /** 当前条目在 remaining 中的起始下标。 */
        final int startInRemaining;
        /** remaining 末尾存在触发补全的空白（multiple 模式下意味着要追加新条目）。 */
        final boolean trailingWs;
        /** remaining 去掉尾随空白后的长度。 */
        final int effLen;

        Token(String text, int startInRemaining, boolean trailingWs, int effLen) {
            this.text = text;
            this.startInRemaining = startInRemaining;
            this.trailingWs = trailingWs;
            this.effLen = effLen;
        }

        /** 建议应插入的起点（相对 builder 起点的偏移）。 */
        int offsetIn(SuggestionsBuilder builder) {
            return builder.getStart() + (trailingWs ? effLen : startInRemaining);
        }
    }

    private static Token splitToken(SuggestionsBuilder builder, Builder b) {
        String remaining = builder.getRemaining();
        int ws = countTrailingWs(remaining);
        int effLen = remaining.length() - ws;
        String content = remaining.substring(0, effLen);

        if (b.multiple && ws > 0) return new Token("", effLen, true, effLen);
        if (b.multiple) {
            int last = lastWhitespace(content);
            if (last >= 0) return new Token(content.substring(last + 1), last + 1, false, effLen);
        }
        return new Token(content, 0, false, effLen);
    }

    // ==================== 补全核心逻辑（包级可见，供单元测试直接驱动） ====================

    /** 位置模式核心逻辑：候选按前缀匹配，无结果时回退子串匹配。 */
    static CompletableFuture<Suggestions> positionalProvide(
            SuggestionsBuilder builder, CommandContext<FabricClientCommandSource> ctx, Builder b) {
        Token token = splitToken(builder, b);
        String[] completed = (b.multiple && token.startInRemaining > 0)
                ? ArgTokenizer.tokenize(builder.getRemaining().substring(0, token.startInRemaining))
                : new String[0];

        List<String> candidates = b.nextProvider.apply(ctx, completed);
        List<String> matched = match(candidates, token.text);

        SuggestionsBuilder out = builder.createOffset(offsetOf(builder, token.offsetIn(builder)));
        for (String c : matched) out.suggest(token.trailingWs ? " " + c : c);
        return out.buildFuture();
    }

    /** 层级模式核心逻辑：分段部分补全 + 完整键，见类 Javadoc。 */
    static CompletableFuture<Suggestions> hierarchicalProvide(
            SuggestionsBuilder builder, CommandContext<FabricClientCommandSource> ctx, Builder b) {
        Collection<String> keys = b.keySource.apply(ctx);
        if (keys == null || keys.isEmpty()) return builder.buildFuture();

        Token token = splitToken(builder, b);

        // assignment=true 且当前条目含 '='：改为补全该键的取值
        if (b.assignment) {
            int eq = token.text.indexOf('=');
            if (eq >= 0) return suggestValues(builder, ctx, b, token, eq);
        }

        List<String> matched = match(keys, token.text);
        // 部分 id 置前，完整键在后
        Set<String> results = new LinkedHashSet<>(partialIds(matched, token.text, b.separators));
        results.addAll(matched);

        SuggestionsBuilder out = builder.createOffset(offsetOf(builder, token.offsetIn(builder)));
        for (String s : results) emit(out, ctx, b, s, keys, token.trailingWs);
        return out.buildFuture();
    }

    /** 输出一条建议；assignment 模式下对完整键追加 {@code =} 及其取值候选。 */
    private static void emit(SuggestionsBuilder out, CommandContext<FabricClientCommandSource> ctx,
                             Builder b, String s, Collection<String> keys, boolean lead) {
        String head = lead ? " " : "";
        if (b.assignment && keys.contains(s)) {
            out.suggest(head + s + "=");
            if (b.valueProvider != null) {
                List<String> vals = b.valueProvider.apply(ctx, s);
                if (vals != null) {
                    for (String v : vals) {
                        if (v != null) out.suggest(head + s + "=" + v);
                    }
                }
            }
            return;
        }
        out.suggest(head + s);
    }

    /** assignment 模式：当前条目已含 {@code =}，补全 {@code =} 之后的取值。 */
    private static CompletableFuture<Suggestions> suggestValues(
            SuggestionsBuilder builder, CommandContext<FabricClientCommandSource> ctx, Builder b,
            Token token, int eq) {
        String keyPart = token.text.substring(0, eq);
        String valFrag = token.text.substring(eq + 1).toLowerCase(Locale.ROOT);
        int offset = builder.getStart() + token.startInRemaining + eq + 1;
        SuggestionsBuilder out = builder.createOffset(offsetOf(builder, offset));
        if (b.valueProvider != null) {
            List<String> vals = b.valueProvider.apply(ctx, keyPart);
            if (vals != null) {
                for (String v : vals) {
                    if (v != null && v.toLowerCase(Locale.ROOT).startsWith(valFrag)) out.suggest(v);
                }
            }
        }
        return out.buildFuture();
    }

    // ==================== 匹配与分段 ====================

    /**
     * 候选筛选：优先前缀匹配（大小写不敏感）；无任何命中时回退为子串匹配，
     * 使 {@code set-auth} 之类的末段片段也能命中长层级键。
     */
    private static List<String> match(Collection<String> candidates, String frag) {
        List<String> out = new ArrayList<>();
        if (candidates == null) return out;
        String lower = frag.toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            if (c != null && c.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(c);
        }
        if (!out.isEmpty() || lower.isEmpty()) return out;
        for (String c : candidates) {
            if (c != null && c.toLowerCase(Locale.ROOT).contains(lower)) out.add(c);
        }
        return out;
    }

    /**
     * 生成「部分 id」建议：取每个候选键中与当前输入段数对应的那一级累积前缀，并做单子节点折叠。
     *
     * <p>仅在候选多于一条、且当前输入确为候选键前缀时生成（子串回退场景下段数无意义，直接给完整键）。</p>
     */
    private static Set<String> partialIds(List<String> matched, String input, String seps) {
        Set<String> out = new LinkedHashSet<>();
        if (matched.size() <= 1 || seps.isEmpty()) return out;
        String lower = input.toLowerCase(Locale.ROOT);
        for (String k : matched) {
            if (!k.toLowerCase(Locale.ROOT).startsWith(lower)) return out; // 子串回退场景
        }
        int level = countSeps(input, seps) + 1;
        for (String k : matched) {
            String prefix = partialPrefixAt(k, level, seps);
            if (prefix == null) continue;
            String collapsed = collapse(prefix, matched, seps);
            if (collapsed != null && collapsed.length() > input.length()) out.add(collapsed);
        }
        return out;
    }

    /**
     * 取 {@code key} 的第 {@code level} 个累积前缀（每段含尾部分隔符）。
     * 例如 {@code a:b.c} 的第 1、2 个前缀分别为 {@code a:}、{@code a:b.}；
     * 若 key 的段数不足（该前缀即完整 key）则返回 {@code null}。
     */
    private static String partialPrefixAt(String key, int level, String seps) {
        int pos = 0;
        for (int i = 0; i < level; i++) {
            int end = segmentEnd(key, pos, seps);
            if (end >= key.length()) return null; // 已到最后一段，没有更短的部分 id
            pos = end;
        }
        return key.substring(0, pos);
    }

    /**
     * 单子节点折叠：从 {@code prefix} 出发，只要下一段在所有覆盖它的候选中唯一就继续下钻，
     * 直到出现分叉为止；若只被一个候选覆盖则返回 {@code null}（该完整键本身已在建议中）。
     */
    private static String collapse(String prefix, List<String> matched, String seps) {
        String cur = prefix;
        while (true) {
            List<String> covered = new ArrayList<>();
            for (String k : matched) {
                if (k.startsWith(cur)) covered.add(k);
            }
            if (covered.size() <= 1) return null;
            Set<String> next = new LinkedHashSet<>();
            for (String k : covered) next.add(k.substring(cur.length(), segmentEnd(k, cur.length(), seps)));
            if (next.size() != 1) return cur;
            String only = next.iterator().next();
            if (only.isEmpty()) return cur;
            cur += only;
        }
    }

    /** 返回从 {@code from} 起下一段的结束下标（含该段尾部的分隔符）；无分隔符时为字符串长度。 */
    private static int segmentEnd(String s, int from, String seps) {
        for (int i = from; i < s.length(); i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) return i + 1;
        }
        return s.length();
    }

    private static int countSeps(String s, String seps) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) n++;
        }
        return n;
    }

    // ==================== 工具方法 ====================

    private static int lastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** 统计字符串末尾连续空白字符的个数（用于识别「触发补全的尾随空格」）。 */
    private static int countTrailingWs(String s) {
        int c = 0;
        for (int i = s.length() - 1; i >= 0 && Character.isWhitespace(s.charAt(i)); i--) c++;
        return c;
    }

    /**
     * 把期望的建议起始偏移钳制到 Brigadier 允许的合法区间 {@code [builder.getStart(), input.length()]}。
     *
     * <p>上界 {@code input.length()}：{@link SuggestionsBuilder#suggest} 产出的区间恒为
     * {@code StringRange.between(start, input.length())}，而 {@code Suggestion.apply} 会执行
     * {@code input.substring(0, range.getStart())}，{@code start} 超出输入长度即抛
     * {@link StringIndexOutOfBoundsException}。</p>
     *
     * <p>下界 {@code builder.getStart()}：偏移不得回退到当前参数起点之前，否则补全会覆盖前面已解析的命令片段。</p>
     *
     * <p>注：装有 SmartCompletion 等在建议窗口构造期即调用 {@code Suggestion.apply} 的模组时，
     * 越界不再只是「点选建议才崩」，而是一打开补全窗口即崩，故该钳制不可省略。</p>
     */
    private static int offsetOf(SuggestionsBuilder builder, int desiredOffset) {
        return Math.max(builder.getStart(), Math.min(desiredOffset, builder.getInput().length()));
    }
}

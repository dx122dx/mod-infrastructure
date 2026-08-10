package com.billy65536.infrastructure.util.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析「层级键=值」格式的参数字串。
 *
 * <p>与 {@link CliCompletion} 配套：后者负责按层级补全 {@code key[=value]}，
 * 本类负责把补全后的整串解析回结构化结果。</p>
 *
 * <p>格式：以空白分隔的若干条目，每条目为 {@code key} 或 {@code key=value}
 * （{@code key} 可含 {@code .} / {@code :} 层级分隔符）。引号包裹的片段整体作为一个条目，
 * 内部可含空白与 {@code =}。返回 {@link Assignment} 列表供命令层逐条处理。</p>
 *
 * <p>每个条目的语义：</p>
 * <ul>
 *   <li>省略 {@code =}（仅写 {@code key}）：{@code value == null} 且 {@code hasValue == false}；</li>
 *   <li>保留 {@code =} 但值为空（{@code key=}）：{@code value == ""} 且 {@code hasValue == true}；</li>
 *   <li>正常（{@code key=v}）：{@code value == "v"}。</li>
 * </ul>
 * 调用方据此区分「未设置」与「设置为空串」两种意图。
 */
public final class ArgParser {

    private ArgParser() {}

    /** 单条 {@code key[=value]} 解析结果。 */
    public static final class Assignment {
        /** 键（不含 {@code =} 与值）。 */
        public final String key;
        /** 值；当且仅当省略 {@code =} 时为 null。空串表示显式空值。 */
        public final String value;
        /** 是否出现 {@code =}：用于区分「未提供值」与「值为空串」。 */
        public final boolean hasValue;

        public Assignment(String key, String value, boolean hasValue) {
            this.key = key;
            this.value = value;
            this.hasValue = hasValue;
        }

        @Override
        public String toString() {
            return hasValue ? key + "=" + value : key;
        }
    }

    /**
     * 解析整串为条目列表，空串或纯空白返回空列表。
     *
     * @param raw 原始参数字串，可为 null
     * @return 条目列表，保证不为 null
     */
    public static List<Assignment> parseAssignments(String raw) {
        List<Assignment> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String tok : ArgTokenizer.tokenize(raw)) {
            Assignment a = parseAssignment(tok);
            if (a != null) out.add(a);
        }
        return out;
    }

    /**
     * 解析单个条目（已切分好的 token）为 {@link Assignment}；null 或空串返回 null。
     *
     * <p>供命令层已自行分词（如调试动作 {@code String[] args}）的场景复用，避免二次分词。</p>
     */
    public static Assignment parseAssignment(String tok) {
        if (tok == null || tok.isEmpty()) return null;
        int eq = tok.indexOf('=');
        if (eq < 0) {
            return new Assignment(tok, null, false);
        }
        return new Assignment(tok.substring(0, eq), tok.substring(eq + 1), true);
    }
}

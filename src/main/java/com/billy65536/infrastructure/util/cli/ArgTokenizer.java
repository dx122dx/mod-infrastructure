package com.billy65536.infrastructure.util.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 带引号感知的命令参数分词工具。
 *
 * <p>命令层用 {@code greedyString} 接收整串原始参数，再由本工具切分为数组，
 * 使用户无需为含空格的参数做引号转义。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>空白分隔 token，连续空白折叠</li>
 *   <li>双引号包裹的片段整体为一个 token，引号本身被剥离</li>
 *   <li>{@code \"} 转义出字面双引号，{@code \\} 转义出反斜杠</li>
 *   <li>未闭合引号宽松处理，视为延伸至串尾（调试工具不应因引号笔误报错）</li>
 *   <li>null 或空白输入返回长度为 0 的数组，调用方无需判空</li>
 * </ul>
 *
 * <p>实现为单趟字符扫描，时间 O(n)、空间 O(n)。</p>
 */
public final class ArgTokenizer {

    private ArgTokenizer() {}

    private static final String[] EMPTY = new String[0];

    /**
     * 将原始参数串切分为参数数组。
     *
     * @param raw 原始参数串，可为 null
     * @return 参数数组，保证不为 null
     */
    public static String[] tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return EMPTY;
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        // 标记当前 token 是否已开始，用于区分空 token（""）与无 token
        boolean started = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    current.append(next);
                    started = true;
                    i++;
                    continue;
                }
                // 非转义序列的反斜杠按字面处理
                current.append(c);
                started = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                // 引号本身剥离，但标记 token 已开始，使 "" 产生一个空参数
                started = true;
                continue;
            }

            if (!inQuotes && Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }

            current.append(c);
            started = true;
        }

        if (started) {
            tokens.add(current.toString());
        }

        return tokens.toArray(EMPTY);
    }
}

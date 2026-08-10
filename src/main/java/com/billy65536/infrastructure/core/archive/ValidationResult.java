package com.billy65536.infrastructure.core.archive;

import java.util.List;

/**
 * 归档包的校验结果。
 *
 * <p>同时携带错误（致命，导致无法安全加载）与警告（不影响加载但值得注意），
 * 调用方应根据 {@link #valid()} 决定是否继续加载。</p>
 *
 * @param valid    是否通过校验（等价于错误列表为空）
 * @param errors   错误信息列表
 * @param warnings 警告信息列表
 */
public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {

    /** 校验全部通过、无任何警告的结果。 */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of(), List.of());
    }

    /**
     * 由错误与警告列表构造结果，{@code valid} 自动由错误列表是否为空推导。
     *
     * @param errors   错误信息列表
     * @param warnings 警告信息列表
     * @return 校验结果（两个列表均被复制为不可变副本）
     */
    public static ValidationResult of(List<String> errors, List<String> warnings) {
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }
}

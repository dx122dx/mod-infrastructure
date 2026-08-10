package com.billy65536.infrastructure.core.io;

import java.io.IOException;

/**
 * 允许抛出 {@link IOException} 的单参数消费者。
 *
 * <p>JDK 的 {@link java.util.function.Consumer} 无法抛出受检异常，
 * 而 I/O 回调几乎必然涉及 {@code IOException}，故单列此接口。
 * 主要供 {@link AtomicFiles} 的回调式写入使用。</p>
 *
 * @param <T> 回调入参类型（通常是 {@link java.io.OutputStream}）
 */
@FunctionalInterface
public interface IoConsumer<T> {

    /**
     * 处理给定入参。
     *
     * @param t 入参
     * @throws IOException 处理过程中的任何 I/O 错误
     */
    void accept(T t) throws IOException;
}

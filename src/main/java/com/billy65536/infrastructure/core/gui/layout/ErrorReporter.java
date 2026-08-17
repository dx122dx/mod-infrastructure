package com.billy65536.infrastructure.core.gui.layout;

/**
 * 布局树错误上报通道。
 *
 * <p>由屏幕容器经 {@link ILayout#setErrorReporter(ErrorReporter)} 注入布局树；
 * 布局节点在渲染 / 事件 / tick 分发的任一环节捕获到异常时调用 {@link #report(Throwable)}，
 * 使容器进入错误隔离态并展示错误详情，避免异常冒泡导致客户端崩溃。</p>
 */
@FunctionalInterface
public interface ErrorReporter {
	/** 上报一次错误。容器应仅响应首次上报（幂等）。 */
	void report(Throwable throwable);
}

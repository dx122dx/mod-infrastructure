package com.billy65536.infrastructure.core.gui.toast;

import org.slf4j.event.Level;

/**
 * toast 消息类型：决定通知左边条颜色与日志级别。
 *
 * <p>四类语义约定：</p>
 * <ul>
 *   <li>{@link #SUCCESS}（绿）——操作成功反馈，日志记 {@code info}；</li>
 *   <li>{@link #WARN}（黄）——告警/可恢复异常，日志记 {@code warn}；</li>
 *   <li>{@link #ERROR}（红）——失败/不可恢复异常，日志记 {@code error}；</li>
 *   <li>{@link #INFO}（青）——常规状态提示，日志记 {@code info}。</li>
 * </ul>
 */
public enum ToastType {

	/** 成功：绿色左边条。 */
	SUCCESS(0xFF55FF55, Level.INFO),
	/** 告警：黄色左边条。 */
	WARN(0xFFFFFF55, Level.WARN),
	/** 错误：红色左边条。 */
	ERROR(0xFFFF5555, Level.ERROR),
	/** 常规信息：青色左边条。 */
	INFO(0xFF55FFFF, Level.INFO);

	private final int accentColor;
	private final Level logLevel;

	ToastType(int accentColor, Level logLevel) {
		this.accentColor = accentColor;
		this.logLevel = logLevel;
	}

	/** toast 左侧类型色竖条颜色（ARGB）。 */
	public int accentColor() {
		return this.accentColor;
	}

	/** 对应 LOGGER 日志级别。 */
	public Level logLevel() {
		return this.logLevel;
	}
}

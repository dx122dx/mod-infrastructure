package com.billy65536.infrastructure.core.gui.layout;

import java.util.function.Supplier;

import net.minecraft.client.font.TextRenderer;

/**
 * 动态文本单元格。
 *
 * <p>显示文本由 {@code supplier} 每帧取值（如「已有数量」每 10 tick 刷新），
 * 渲染时立即读取最新值；列宽按 {@code fallbackWidth} 预分配（如按「9999」测量），
 * 避免内容变化导致列宽抖动。</p>
 *
 * @param supplier      文本提供者（每帧调用）
 * @param color         ARGB 颜色值
 * @param align         列内对齐方式
 * @param fallbackWidth 预分配列宽（像素），供布局计算
 */
public record DynamicTextCell(Supplier<String> supplier, int color,
		TableLayout.ColumnSpec.Align align, int fallbackWidth) implements IContentCell {

	public static DynamicTextCell of(Supplier<String> supplier, int color, int fallbackWidth) {
		return new DynamicTextCell(supplier, color, TableLayout.ColumnSpec.Align.LEFT, fallbackWidth);
	}

	public static DynamicTextCell of(Supplier<String> supplier, int color,
			TableLayout.ColumnSpec.Align align, int fallbackWidth) {
		return new DynamicTextCell(supplier, color, align, fallbackWidth);
	}

	/** 渲染时读取最新值。 */
	public String current() {
		String value = supplier.get();
		return value == null ? "" : value;
	}

	@Override
	public int cellWidth(TextRenderer renderer) {
		return fallbackWidth;
	}
}

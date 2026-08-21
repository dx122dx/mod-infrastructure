package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * 按钮文本单元格。
 *
 * <p>渲染为带颜色的文本（悬停时切换为高亮色），点击时执行回调。
 * 用于表格行内的操作按钮（例如文件列表的恢复/删除/打开）。</p>
 *
 * @param display    显示文本
 * @param color      普通状态颜色（ARGB）
 * @param hoverColor 悬停状态颜色（ARGB）
 * @param onClick    点击回调，{@code null} 表示不可点击
 */
public record ButtonCell(Text display, int color, int hoverColor, Runnable onClick) implements IContentCell {

	/** 创建默认白/黄按钮单元格。 */
	public static ButtonCell of(Text display, Runnable onClick) {
		return new ButtonCell(display, 0xFFFFFFFF, 0xFFFFFF55, onClick);
	}

	/** 创建默认白/黄按钮单元格。 */
	public static ButtonCell of(String display, Runnable onClick) {
		return ButtonCell.of(Text.literal(display), onClick);
	}

	@Override
	public int cellWidth(TextRenderer renderer) {
		return renderer.getWidth(display);
	}
}

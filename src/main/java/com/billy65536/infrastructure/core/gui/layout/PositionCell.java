package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * 位置文本单元格（与具体位置模型解耦）。
 *
 * <p>渲染为青色文本（悬停时高亮为黄色），可携带可选点击回调（例如点击创建路径点）。
 * 不直接依赖任何位置类型，由业务层负责把位置转成显示文本并注入回调。</p>
 *
 * @param display 显示文本
 * @param onClick 点击回调，{@code null} 表示不可点击
 */
public record PositionCell(Text display, Runnable onClick) implements IContentCell {

	public static PositionCell of(Text display, Runnable onClick) {
		return new PositionCell(display, onClick);
	}

	public static PositionCell of(String display, Runnable onClick) {
		return new PositionCell(Text.literal(display), onClick);
	}

	@Override
	public int cellWidth(TextRenderer renderer) {
		return renderer.getWidth(display);
	}
}

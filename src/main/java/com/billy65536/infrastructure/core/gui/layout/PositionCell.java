package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * 位置文本单元格（与具体位置模型解耦）。
 *
 * <p>渲染为青色文本（悬停时高亮为黄色），可携带可选左键/右键回调（例如左键创建路径点、
 * 右键加入导航队列）。不直接依赖任何位置类型，由业务层负责把位置转成显示文本并注入回调。</p>
 *
 * @param display 显示文本
 * @param onClick 左键回调，{@code null} 表示左键不可点击
 * @param onRightClick 右键回调，{@code null} 表示右键不可点击
 */
public record PositionCell(Text display, Runnable onClick, Runnable onRightClick) implements IContentCell {

	public static PositionCell of(Text display, Runnable onClick) {
		return new PositionCell(display, onClick, null);
	}

	public static PositionCell of(String display, Runnable onClick) {
		return new PositionCell(Text.literal(display), onClick, null);
	}

	public static PositionCell of(Text display, Runnable onClick, Runnable onRightClick) {
		return new PositionCell(display, onClick, onRightClick);
	}

	public static PositionCell of(String display, Runnable onClick, Runnable onRightClick) {
		return new PositionCell(Text.literal(display), onClick, onRightClick);
	}

	@Override
	public int cellWidth(TextRenderer renderer) {
		return renderer.getWidth(display);
	}
}

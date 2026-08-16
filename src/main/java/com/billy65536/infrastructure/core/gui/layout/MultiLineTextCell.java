package com.billy65536.infrastructure.core.gui.layout;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * 多行文本单元格。
 *
 * <p>支持最多 {@code maxLines} 行、统一的 {@code color} 着色、
 * {@code scale} 缩放与 {@code lineHeight} 行高（行高单位与缩放无关，为固定像素）。</p>
 *
 * @param lines      原始行数组（按原样保留，渲染时截断到 maxLines 行）
 * @param color      ARGB 颜色值
 * @param scale      文本缩放（{@code 1.0f} 为原尺寸，一般传 {@code 0.8f}）
 * @param lineHeight 每行像素高度（含行距）
 * @param maxLines   最多显示行数
 */
public record MultiLineTextCell(String[] lines, int color, float scale, int lineHeight, int maxLines) implements IContentCell {

	/**
	 * 便捷工厂：从 {@link List} 构建。
	 *
	 * @param lines      原始行集合
	 * @param color      颜色
	 * @param scale      缩放
	 * @param lineHeight 行高
	 * @param maxLines   最多行数
	 */
	public static MultiLineTextCell of(List<String> lines, int color, float scale, int lineHeight, int maxLines) {
		return new MultiLineTextCell(lines.toArray(String[]::new), color, scale, lineHeight, maxLines);
	}

	/** 取渲染时实际显示的行（截断到 maxLines 行，且剔除末尾空行）。 */
	public List<String> displayLines() {
		List<String> result = new ArrayList<>();
		for (int i = 0; i < lines.length && i < maxLines; i++) {
			if (!lines[i].isEmpty()) {
				result.add(lines[i]);
			}
		}
		return result;
	}

	/** 渲染总高度（显示行数 × 行高）。 */
	public int totalHeight() {
		return displayLines().size() * lineHeight;
	}

	@Override
	public int cellWidth(TextRenderer renderer) {
		int max = 0;
		for (String line : displayLines()) {
			max = Math.max(max, (int) (renderer.getWidth(line) * scale));
		}
		return max;
	}
}

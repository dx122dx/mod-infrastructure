package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.font.TextRenderer;

/**
 * 表格单元格内容的密封接口。
 *
 * <p>每个单元格可以是以下五种类型之一：
 * <ul>
 *   <li>{@link TextCell} — 带颜色和可选 tooltip 的文本</li>
 *   <li>{@link PositionCell} — 位置文本（青色，悬停黄色，可选点击回调）</li>
 *   <li>{@link ItemCell} — 物品图标（16×16）</li>
 *   <li>{@link MultiLineTextCell} — 多行文本（缩放/行高可配）</li>
 *   <li>{@link DynamicTextCell} — 动态文本（每帧从 Supplier 取值刷新）</li>
 *   <li>{@link ButtonCell} — 按钮文本（普通/悬停双色，点击回调）</li>
 * </ul>
 *
 * <p>渲染时通过 {@code instanceof} 分派到对应渲染逻辑。</p>
 */
public sealed interface IContentCell permits TextCell, PositionCell, ItemCell, MultiLineTextCell, DynamicTextCell, ButtonCell {
	/** 计算该单元格所需的最小列宽（不含内边距）。 */
	int cellWidth(TextRenderer renderer);
}

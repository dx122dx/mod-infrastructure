package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;

/**
 * 物品图标单元格。
 *
 * <p>渲染为 16×16 物品图标（覆盖文本显示），
 * 悬停时显示原版物品 tooltip（由外层统一处理）。</p>
 *
 * @param stack 要展示的物品堆
 */
public record ItemCell(ItemStack stack) implements IContentCell {

	public static ItemCell of(ItemStack stack) {
		return new ItemCell(stack);
	}

	@Override
	public int cellWidth(TextRenderer renderer) {
		return 18;
	}
}

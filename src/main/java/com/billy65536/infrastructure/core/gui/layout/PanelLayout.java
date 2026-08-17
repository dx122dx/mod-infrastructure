package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * 窗口容器布局：带标题栏与背景的类窗口面板，子控件沿纵向流式排布。
 *
 * <p>PanelLayout 承担「窗口」职责：</p>
 * <ul>
 *   <li>绘制背景（半透明深色 + 边框）；</li>
 *   <li>绘制标题栏（居中金色标题 + 分隔线）；</li>
 *   <li>{@link #layout()} 将子节点沿纵向依次排布（从标题栏下方开始，每行高度由子节点自身决定）。</li>
 * </ul>
 *
 * <p>业务层典型用法：<code>panel.setTitle(...); panel.addChild(table);</code> 随后交给 ScreenContainer
 * 统一渲染。TableLayout 等子布局的宽度由布局树决定，高度由子节点自身内容决定。</p>
 */
public class PanelLayout extends AbstractLayout {

	/** 背景色：不透明黑底，配合边框营造窗口感。 */
	private static final int BACKGROUND_COLOR = 0xC0101010;
	/** 边框颜色：暗灰。 */
	private static final int BORDER_COLOR = 0xFF555555;
	/** 面板内边距（左右与标题下方）。 */
	private static final int PADDING = 10;
	/** 标题栏高度。 */
	private static final int TITLE_BAR_HEIGHT = 24;

	private Text title;

	public PanelLayout() {
	}

	/**
	 * 设置窗口标题（渲染于标题栏中央，金色加粗）。
	 */
	public PanelLayout setTitle(Text title) {
		this.title = title;
		return this;
	}

	/**
	 * 纵向流式排布子节点：从标题栏下方开始，每个子节点占满可用宽度，
	 * 高度取子节点自身 {@link ILayout#getHeight()}。坐标均为相对本面板（局部坐标系）。
	 */
	@Override
	public void layout() {
		if (this.children == null || this.children.isEmpty()) {
			return;
		}
		int cursorY = TITLE_BAR_HEIGHT + 4;
		int childWidth = this.width - PADDING * 2;
		for (ILayout child : this.children) {
			child.setBounds(PADDING, cursorY, childWidth, child.getHeight());
			child.layout();
			cursorY += child.getHeight() + 6;
		}
	}

	@Override
	public void init() {
		super.init();
	}

	@Override
	protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
		if (this.width <= 0 || this.height <= 0) {
			return;
		}
		// 背景（局部坐标系：本面板左上角为原点）
		ctx.fill(0, 0, this.width, this.height, BACKGROUND_COLOR);
		// 边框
		ctx.drawBorder(0, 0, this.width, this.height, BORDER_COLOR);
		// 标题（居中金色）
		if (this.title != null) {
			TextRenderer tr = MinecraftClient.getInstance().textRenderer;
			ctx.drawCenteredTextWithShadow(tr, this.title,
				this.width / 2, (TITLE_BAR_HEIGHT - 8) / 2, 0xFFDAA520);
			// 标题下方分隔线
			ctx.fill(PADDING, TITLE_BAR_HEIGHT,
				this.width - PADDING, TITLE_BAR_HEIGHT + 1, 0xFF888888);
		}
	}
}

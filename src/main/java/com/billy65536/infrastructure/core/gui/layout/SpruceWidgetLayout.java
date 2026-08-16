package com.billy65536.infrastructure.core.gui.layout;

import dev.lambdaurora.spruceui.widget.SpruceWidget;
import net.minecraft.client.gui.DrawContext;

/**
 * 将 SpruceUI 控件（SpruceButtonWidget / SpruceTextFieldWidget 等）包装为 ILayout 节点，
 * 使其能加入布局树并随 ScreenContainer 统一渲染与分发事件。
 *
 * <p>控件位置经其内部 {@link SpruceWidget#getPosition()} 的 relativeX/relativeY 移动；
 * 宽高为控件构造时的固有值，不可由布局树改变（SpruceWidget 接口未暴露 setWidth/setHeight）。</p>
 */
public class SpruceWidgetLayout implements ILayout {

	/** 被包装的 SpruceUI 控件。 */
	private final SpruceWidget widget;

	public SpruceWidgetLayout(SpruceWidget widget) {
		this.widget = widget;
	}

	/** 返回被包装的底层控件，便于业务层访问 SpruceUI 专属 API（如 setTooltip / setChangedListener）。 */
	public SpruceWidget getWidget() {
		return this.widget;
	}

	@Override
	public void setBounds(int x, int y, int width, int height) {
		this.widget.getPosition().setRelativeX(x);
		this.widget.getPosition().setRelativeY(y);
		// SpruceWidget 宽高为构造固有值，width/height 参数仅占位，不改变控件尺寸。
	}

	@Override
	public int getX() {
		return this.widget.getX();
	}

	@Override
	public int getY() {
		return this.widget.getY();
	}

	@Override
	public int getWidth() {
		return this.widget.getWidth();
	}

	@Override
	public int getHeight() {
		return this.widget.getHeight();
	}

	@Override
	public void layout() {
		// 叶子节点：无子节点需要排布。
	}

	@Override
	public void init() {
		// 控件自身无需初始化。
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.widget.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public void tick() {
		// SpruceWidget 接口不暴露 tick（控件内务由 SpruceUI 内部处理），无需转发。
	}

	@Override
	public boolean isVisible() {
		return this.widget.isVisible();
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return this.widget.isMouseOver(mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return this.widget.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return this.widget.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		return this.widget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return this.widget.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return this.widget.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int keyCode) {
		return this.widget.charTyped(chr, keyCode);
	}

	@Override
	public void addChild(ILayout child) {
		throw new UnsupportedOperationException("SpruceWidgetLayout 是叶子节点，不支持添加子节点");
	}

	@Override
	public java.util.List<ILayout> getChildren() {
		return java.util.Collections.emptyList();
	}
}

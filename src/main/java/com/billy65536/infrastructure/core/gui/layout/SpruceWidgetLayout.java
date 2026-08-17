package com.billy65536.infrastructure.core.gui.layout;

import dev.lambdaurora.spruceui.widget.SpruceWidget;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 SpruceUI 控件（SpruceButtonWidget / SpruceTextFieldWidget 等）包装为 ILayout 节点，
 * 使其能加入布局树并随 ScreenContainer 统一渲染与分发事件。
 *
 * <h2>坐标约定（相对父组件坐标系）</h2>
 * <ul>
 *   <li>{@link #setBounds(int, int, int, int)} 的 x/y 经 {@code setRelativeX/Y} 写入
 *       Spruce 的 {@link SpruceWidget#getPosition()}（anchor 为 origin），
 *       {@link #getX()}/{@link #getY()} 返回 Position 链求和坐标，即相对父组件的局部坐标；</li>
 *   <li>父布局渲染 {@code translate} 后，Spruce 控件用 getX() 绘制、用同一 getX() 命中，
 *       与传入的局部鼠标坐标一致，无双重偏移；</li>
 *   <li>宽高为控件构造时的固有值，不可由布局树改变（SpruceWidget 接口未暴露 setWidth/setHeight）。</li>
 * </ul>
 */
public class SpruceWidgetLayout implements ILayout {

	/** 被包装的 SpruceUI 控件。 */
	private final SpruceWidget widget;

	/** 错误上报通道（由 ScreenContainer 注入；null 表示未接入错误隔离）。 */
	@Nullable
	private ErrorReporter errorReporter;

	private static final Logger LOGGER = LoggerFactory.getLogger(SpruceWidgetLayout.class);

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
		try {
			this.widget.render(ctx, mouseX, mouseY, delta);
		} catch (Throwable t) {
			this.reportError(t);
		}
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
		try {
			return this.widget.mouseClicked(mouseX, mouseY, button);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		try {
			return this.widget.mouseReleased(mouseX, mouseY, button);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		try {
			return this.widget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		try {
			return this.widget.mouseScrolled(mouseX, mouseY, amount);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		try {
			return this.widget.keyPressed(keyCode, scanCode, modifiers);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	@Override
	public boolean charTyped(char chr, int keyCode) {
		try {
			return this.widget.charTyped(chr, keyCode);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	@Override
	public void setErrorReporter(ErrorReporter reporter) {
		this.errorReporter = reporter;
	}

	/**
	 * 捕获控件异常：上报给容器进入错误隔离态；无上报通道时记录日志并吞掉，避免客户端崩溃。
	 */
	private void reportError(Throwable t) {
		if (this.errorReporter != null) {
			this.errorReporter.report(t);
		} else {
			LOGGER.error("SpruceWidgetLayout 捕获未隔离异常（无上报通道）", t);
		}
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

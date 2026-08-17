package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 布局控件统一抽象：控件与容器的共同接口。
 *
 * <p>ILayout 将「控件」「容器」收敛为同一抽象：任何节点都能被渲染、接收输入事件、
 * 持有子节点。布局树由 ScreenContainer 持有根节点，经 init/layout 后按帧递归分发
 * render / tick / 鼠标键盘事件。</p>
 *
 * <p>事件方法返回 boolean：true 表示该事件已被消费，父级不应再处理。</p>
 */
public interface ILayout {
	// ---- 布局与尺寸 ----

	/** 设置节点位置与尺寸（绝对坐标，相对父容器偏移后）。 */
	void setBounds(int x, int y, int width, int height);

	int getX();

	int getY();

	int getWidth();

	int getHeight();

	/** 重新计算子节点布局。由父容器在自身尺寸变化后调用。 */
	void layout();

	// ---- 生命周期 ----

	/** 初始化：创建子控件、注册监听等。在布局树 init 阶段由根到叶子递归调用。 */
	void init();

	/** 每帧渲染。mouseX/mouseY 为屏幕绝对坐标。 */
	void render(DrawContext ctx, int mouseX, int mouseY, float delta);

	/** 每 tick 调用（20 次/秒）。 */
	void tick();

	boolean isVisible();

	// ---- 鼠标事件（绝对坐标；true=消费） ----

	boolean isMouseOver(double mouseX, double mouseY);

	boolean mouseClicked(double mouseX, double mouseY, int button);

	boolean mouseReleased(double mouseX, double mouseY, int button);

	boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY);

	boolean mouseScrolled(double mouseX, double mouseY, double amount);

	// ---- 键盘事件（true=消费） ----

	boolean keyPressed(int keyCode, int scanCode, int modifiers);

	boolean charTyped(char chr, int keyCode);

	// ---- 错误隔离 ----

	/**
	 * 注入错误上报通道并沿子树递归下发。
	 * 默认空实现：叶子节点无子节点可下发，忽略即可。
	 */
	default void setErrorReporter(ErrorReporter reporter) {
	}

	// ---- 子节点管理 ----

	void addChild(ILayout child);

	@Nullable
	List<ILayout> getChildren();
}

package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 布局树基类：子节点管理 + 坐标换算 + 递归渲染与事件分发。
 *
 * <p>容器类布局（PanelLayout / TableLayout 等）继承本类获得统一的子树行为。</p>
 *
 * <h2>坐标约定（相对父组件坐标系）</h2>
 * <ul>
 *   <li>{@link #setBounds(int, int, int, int)} 的 x/y 为本节点相对父组件的偏移，
 *       渲染与事件一律在「局部坐标系」（本节点左上角为原点）中进行；</li>
 *   <li>渲染：父节点 {@code push → translate(this.x, this.y)} 后，本节点在局部坐标系
 *       绘制自身与子节点（子节点递归同样换算）；</li>
 *   <li>事件：父节点把 {@code (mouseX - this.x, mouseY - this.y)} 换算后的局部坐标
 *       传给本节点与子节点；</li>
 *   <li>屏幕级定位（scissor / tooltip）使用渲染时在 translate 前维护的
 *       {@link #absX}/{@link #absY}（屏幕绝对坐标），业务层与组件内部禁止
 *       自行拼接绝对坐标。</li>
 * </ul>
 *
 * <p>渲染顺序：先渲染自身，再按序渲染子节点；
 * 事件分发顺序：先命中子节点（后加入者优先，模拟 Z 序），未被消费再由自身处理；
 * init/layout/tick 沿子树递归。</p>
 */
public abstract class AbstractLayout implements ILayout {

	protected int x;
	protected int y;
	protected int width;
	protected int height;

	/** 本节点左上角的屏幕绝对逻辑坐标（逻辑像素 = 物理像素 ÷ GUI scale；render 在 translate 前维护；scissor 等屏幕级定位用）。 */
	protected int absX;
	protected int absY;

	/** 子节点列表；null 表示该节点不允许/尚未持有子节点。 */
	@Nullable
	protected List<ILayout> children;

	/** 错误上报通道（由 ScreenContainer 注入；null 表示未接入错误隔离）。 */
	@Nullable
	private ErrorReporter errorReporter;

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractLayout.class);

	@Override
	public void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	@Override
	public int getX() {
		return this.x;
	}

	@Override
	public int getY() {
		return this.y;
	}

	@Override
	public int getWidth() {
		return this.width;
	}

	@Override
	public int getHeight() {
		return this.height;
	}

	@Override
	public void addChild(ILayout child) {
		if (this.children == null) {
			this.children = new ArrayList<>();
		}
		this.children.add(child);
		// 运行期动态加入的子节点（如编辑器等）继承当前上报通道
		if (this.errorReporter != null) {
			child.setErrorReporter(this.errorReporter);
		}
	}

	@Override
	@Nullable
	public List<ILayout> getChildren() {
		return this.children == null ? Collections.emptyList() : this.children;
	}

	@Override
	public void setErrorReporter(ErrorReporter reporter) {
		this.errorReporter = reporter;
		if (this.children != null) {
			for (ILayout child : this.children) {
				child.setErrorReporter(reporter);
			}
		}
	}

	/**
	 * 捕获布局异常：上报给容器进入错误隔离态；未接入上报通道时记录日志并吞掉，避免客户端崩溃。
	 */
	private void reportError(Throwable t) {
		if (this.errorReporter != null) {
			this.errorReporter.report(t);
		} else {
			LOGGER.error("布局节点捕获未隔离异常（无上报通道）", t);
		}
	}

	/**
	 * 默认布局实现：对子节点不自动排布（保持其 setBounds 设定的相对父坐标）。
	 * 需要自动排布的容器覆盖 {@link #layout()} 自行实现。
	 */
	@Override
	public void layout() {
		// 子节点坐标由业务层 setBounds 显式指定，或由子类覆盖本方法自动排布。
	}

	/**
	 * 递归初始化整棵子树。
	 */
	@Override
	public void init() {
		if (this.children != null) {
			for (ILayout child : this.children) {
				child.init();
			}
		}
	}

	/**
	 * 递归渲染：先维护屏幕坐标，再 push/translate 到局部坐标系，先自身后子树。
	 */
	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		try {
			// translate 前：把本节点左上角变换为屏幕坐标（供 scissor 等屏幕级定位）。
			// 模型矩阵栈（DrawContext.getMatrices()）为纯 model 栈、不含 GUI scale；
			// 投影矩阵本身已除以 scale（GameRenderer: setOrtho(0, fbW/scale, fbH/scale, ...)），
			// 故 screenPos 即为 GUI 逻辑坐标，直接使用；若再除以 scale 会导致 scale≥2 时
			// absX/absY 偏小，scissor 框上移左移，表格底部与横向滚动条被误裁剪。
			Vector4f screenPos = ctx.getMatrices().peek().getPositionMatrix()
					.transform(new Vector4f(this.x, this.y, 0.0f, 1.0f));
			this.absX = (int) Math.round(screenPos.x);
			this.absY = (int) Math.round(screenPos.y);

			ctx.getMatrices().push();
			ctx.getMatrices().translate(this.x, this.y, 0);
			int localMouseX = mouseX - this.x;
			int localMouseY = mouseY - this.y;
			this.renderSelf(ctx, localMouseX, localMouseY, delta);
			if (this.children != null) {
				for (ILayout child : this.children) {
					if (child.isVisible()) {
						child.render(ctx, localMouseX, localMouseY, delta);
					}
				}
			}
			ctx.getMatrices().pop();
		} catch (Throwable t) {
			this.reportError(t);
		}
	}

	/**
	 * 子类覆盖：渲染自身内容（不含子节点）。坐标均为本节点局部坐标系（左上角为原点）。
	 */
	protected abstract void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta);

	/**
	 * 递归 tick。
	 */
	@Override
	public void tick() {
		try {
			if (this.children != null) {
				for (ILayout child : this.children) {
					child.tick();
				}
			}
		} catch (Throwable t) {
			this.reportError(t);
		}
	}

	@Override
	public boolean isVisible() {
		return true;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		// 局部坐标判断（父节点已换算传入）
		return mouseX >= 0 && mouseX <= this.width
			&& mouseY >= 0 && mouseY <= this.height;
	}

	// ---- 事件分发 ----

	/**
	 * 先向命中的子节点分发（后加入者优先），未被消费再由自身处理。
	 * 事件坐标相对自身偏移换算后传入子节点。
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		try {
			double localX = mouseX - this.x;
			double localY = mouseY - this.y;
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(localX, localY)
						&& child.mouseClicked(localX, localY, button)) {
						return true;
					}
				}
			}
			return this.onMouseClicked(localX, localY, button);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	/**
	 * 子类覆盖：自身鼠标点击处理。坐标为局部坐标。
	 */
	protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		try {
			double localX = mouseX - this.x;
			double localY = mouseY - this.y;
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(localX, localY)
						&& child.mouseReleased(localX, localY, button)) {
						return true;
					}
				}
			}
			return this.onMouseReleased(localX, localY, button);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	protected boolean onMouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		try {
			double localX = mouseX - this.x;
			double localY = mouseY - this.y;
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(localX, localY)
						&& child.mouseDragged(localX, localY, button, deltaX, deltaY)) {
						return true;
					}
				}
			}
			return this.onMouseDragged(localX, localY, button, deltaX, deltaY);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	protected boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		try {
			double localX = mouseX - this.x;
			double localY = mouseY - this.y;
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(localX, localY)
						&& child.mouseScrolled(localX, localY, amount)) {
						return true;
					}
				}
			}
			return this.onMouseScrolled(localX, localY, amount);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		try {
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.keyPressed(keyCode, scanCode, modifiers)) {
						return true;
					}
				}
			}
			return this.onKeyPressed(keyCode, scanCode, modifiers);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
		return false;
	}

	@Override
	public boolean charTyped(char chr, int keyCode) {
		try {
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.charTyped(chr, keyCode)) {
						return true;
					}
				}
			}
			return this.onCharTyped(chr, keyCode);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	protected boolean onCharTyped(char chr, int keyCode) {
		return false;
	}
}

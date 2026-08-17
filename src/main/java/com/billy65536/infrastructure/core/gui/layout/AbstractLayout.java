package com.billy65536.infrastructure.core.gui.layout;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 布局树基类：子节点管理 + 坐标换算 + 递归渲染与事件分发。
 *
 * <p>容器类布局（PanelLayout / TableLayout 等）继承本类获得统一的子树行为：</p>
 * <ul>
 *   <li>子节点以绝对坐标存储（相对布局树根 ScreenContainer），事件坐标直接透传无需换算；</li>
 *   <li>渲染顺序：先渲染自身，再按序渲染子节点；</li>
 *   <li>事件分发顺序：先命中子节点（后加入者优先，模拟 Z 序），未被消费再由自身处理；</li>
 *   <li>init/layout/tick 沿子树递归。</li>
 * </ul>
 */
public abstract class AbstractLayout implements ILayout {

	protected int x;
	protected int y;
	protected int width;
	protected int height;

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
	 * 默认布局实现：对子节点不自动排布（保持其 setBounds 设定的绝对坐标）。
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
	 * 递归渲染：先自身后子树。
	 */
	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		try {
			this.renderSelf(ctx, mouseX, mouseY, delta);
			if (this.children != null) {
				for (ILayout child : this.children) {
					if (child.isVisible()) {
						child.render(ctx, mouseX, mouseY, delta);
					}
				}
			}
		} catch (Throwable t) {
			this.reportError(t);
		}
	}

	/**
	 * 子类覆盖：渲染自身内容（不含子节点）。
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
		return mouseX >= this.x && mouseX <= this.x + this.width
			&& mouseY >= this.y && mouseY <= this.y + this.height;
	}

	// ---- 事件分发 ----

	/**
	 * 先向命中的子节点分发（后加入者优先），未被消费再由自身处理。
	 * 事件坐标相对自身偏移换算后传入子节点。
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		try {
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(mouseX, mouseY)
						&& child.mouseClicked(mouseX, mouseY, button)) {
						return true;
					}
				}
			}
			return this.onMouseClicked(mouseX, mouseY, button);
		} catch (Throwable t) {
			this.reportError(t);
			return true;
		}
	}

	/**
	 * 子类覆盖：自身鼠标点击处理。
	 */
	protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		try {
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(mouseX, mouseY)
						&& child.mouseReleased(mouseX, mouseY, button)) {
						return true;
					}
				}
			}
			return this.onMouseReleased(mouseX, mouseY, button);
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
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(mouseX, mouseY)
						&& child.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
						return true;
					}
				}
			}
			return this.onMouseDragged(mouseX, mouseY, button, deltaX, deltaY);
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
			if (this.children != null) {
				for (int i = this.children.size() - 1; i >= 0; i--) {
					ILayout child = this.children.get(i);
					if (child.isVisible() && child.isMouseOver(mouseX, mouseY)
						&& child.mouseScrolled(mouseX, mouseY, amount)) {
						return true;
					}
				}
			}
			return this.onMouseScrolled(mouseX, mouseY, amount);
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

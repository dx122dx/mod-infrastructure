package com.billy65536.infrastructure.core.gui;

import com.billy65536.infrastructure.core.gui.layout.ILayout;
import dev.lambdaurora.spruceui.Tooltip;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * 统一窗口容器：持有布局树根节点，管理渲染 / 输入事件 / tick 的递归分发。
 *
 * <p>业务层（qab 等）创建布局树（PanelLayout + TableLayout 等），经 {@link #setLayout(ILayout)}
 * 注入本容器；本类负责：</p>
 * <ul>
 *   <li>{@link #init()} 时对根节点 setBounds(0,0,宽高) 并递归 init/layout；</li>
 *   <li>{@link #render} 绘制默认背景后递归渲染布局树，并刷新 SpruceUI Tooltip；</li>
 *   <li>鼠标/键盘/tick 事件转发给根节点递归分发（根未消费时回落 SpruceScreen 默认行为）。</li>
 * </ul>
 *
 * <p>关闭行为沿用 Screen 默认（返回父屏幕），业务层无需覆写。</p>
 */
public class ScreenContainer extends SpruceScreen {

	/** 布局树根节点。 */
	@Nullable
	private ILayout root;

	public ScreenContainer(Text title) {
		super(title);
	}

	/**
	 * 注入布局树根节点。
	 */
	public void setLayout(ILayout root) {
		this.root = root;
	}

	@Nullable
	public ILayout getLayout() {
		return this.root;
	}

	@Override
	protected void init() {
		super.init();
		if (this.root != null) {
			this.root.setBounds(0, 0, this.width, this.height);
			this.root.init();
			this.root.layout();
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);
		if (this.root != null) {
			this.root.render(ctx, mouseX, mouseY, delta);
		}
		Tooltip.renderAll(ctx);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.root != null) {
			this.root.tick();
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.root != null && this.root.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (this.root != null && this.root.mouseReleased(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (this.root != null && this.root.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (this.root != null && this.root.mouseScrolled(mouseX, mouseY, amount)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.root != null && this.root.keyPressed(keyCode, scanCode, modifiers)) {
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int keyCode) {
		if (this.root != null && this.root.charTyped(chr, keyCode)) {
			return true;
		}
		return super.charTyped(chr, keyCode);
	}
}

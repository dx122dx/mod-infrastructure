package com.billy65536.infrastructure.core.gui;

import com.billy65536.infrastructure.core.gui.layout.ErrorDisplayLayout;
import com.billy65536.infrastructure.core.gui.layout.ILayout;
import com.billy65536.infrastructure.core.gui.toast.ToastQueue;
import dev.lambdaurora.spruceui.Tooltip;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一窗口容器：持有布局树根节点，管理渲染 / 输入事件 / tick 的递归分发。
 *
 * <p>业务层（qab 等）创建布局树（PanelLayout + TableLayout 等），经 {@link #setLayout(ILayout)}
 * 注入本容器；本类负责：</p>
 * <ul>
 *   <li>{@link #init()} 时对根节点 setBounds(0,0,宽高) 并递归 init/layout；</li>
 *   <li>{@link #render} 绘制默认背景后递归渲染布局树，刷新 SpruceUI Tooltip，
 *       并渲染 toast 队列（错误隔离态同样保留 toast 渲染）；</li>
 *   <li>鼠标/键盘/tick 事件转发给根节点递归分发（根未消费的点击先尝试命中 toast 关闭对应条目，
 *       再回落 SpruceScreen 默认行为）；</li>
 *   <li>错误隔离：布局树在渲染 / 事件 / tick 任一环节抛出异常时，由节点级捕获上报本容器，
 *       进入错误隔离态并展示错误详情（可滚动 / 导出 / 关闭），避免异常冒泡导致客户端崩溃。</li>
 * </ul>
 *
 * <p>关闭行为沿用 Screen 默认（返回父屏幕），业务层无需覆写。</p>
 */
public class ScreenContainer extends SpruceScreen {

	/** 错误隔离态操作按钮高度。 */
	private static final int ERROR_BUTTON_HEIGHT = 20;
	/** 错误隔离态按钮距屏幕边缘的边距。 */
	private static final int ERROR_BUTTON_MARGIN = 8;
	/** 错误隔离态两个按钮之间的间距。 */
	private static final int ERROR_BUTTON_GAP = 6;

	private static final Logger LOGGER = LoggerFactory.getLogger(ScreenContainer.class);
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** 布局树根节点。 */
	@Nullable
	private ILayout root;

	/** 当前被隔离的致命错误；非 null 表示已进入错误隔离态。 */
	@Nullable
	private Throwable fatalError;

	/** 错误详情展示布局（错误隔离态使用）。 */
	@Nullable
	private ErrorDisplayLayout errorLayout;

	/** 错误隔离态按钮：导出错误详情到文件。 */
	@Nullable
	private ButtonWidget exportButton;

	/** 错误隔离态按钮：关闭并返回父屏幕。 */
	@Nullable
	private ButtonWidget closeButton;

	public ScreenContainer(Text title) {
		super(title);
	}

	/**
	 * 注入布局树根节点，并沿树注入错误上报通道。
	 */
	public void setLayout(ILayout root) {
		this.root = root;
		if (root != null) {
			root.setErrorReporter(this::onLayoutError);
		}
	}

	@Nullable
	public ILayout getLayout() {
		return this.root;
	}

	/** 是否已进入错误隔离态（业务布局停止渲染与响应，仅展示错误详情）。 */
	public boolean isErrorState() {
		return this.fatalError != null;
	}

	// ---- 错误隔离 ----

	/**
	 * 布局树错误上报入口（幂等）：记录日志、进入错误隔离态并装配错误展示界面。
	 */
	private void onLayoutError(Throwable t) {
		if (this.fatalError != null) {
			return;
		}
		this.fatalError = t;
		LOGGER.error("布局错误已被隔离，进入错误展示态", t);
		this.errorLayout = new ErrorDisplayLayout(this.textRenderer, t);
		this.exportButton = ButtonWidget.builder(Text.literal("导出错误详情"), b -> this.exportErrorDetails())
				.dimensions(0, 0, 110, ERROR_BUTTON_HEIGHT).build();
		this.closeButton = ButtonWidget.builder(Text.literal("关闭"), b -> this.close())
				.dimensions(0, 0, 60, ERROR_BUTTON_HEIGHT).build();
		this.addDrawableChild(this.exportButton);
		this.addDrawableChild(this.closeButton);
		this.layoutErrorState();
	}

	/** 排布错误隔离态界面：错误详情铺满内容区，操作按钮置于右下角。 */
	private void layoutErrorState() {
		if (this.errorLayout != null) {
			this.errorLayout.setBounds(0, 0, this.width, this.height - ERROR_BUTTON_HEIGHT - ERROR_BUTTON_MARGIN * 2);
		}
		int closeW = 60;
		int exportW = 110;
		int y = this.height - ERROR_BUTTON_HEIGHT - ERROR_BUTTON_MARGIN;
		int x = this.width - ERROR_BUTTON_MARGIN - closeW;
		if (this.closeButton != null) {
			this.closeButton.setPosition(x, y);
		}
		if (this.exportButton != null) {
			this.exportButton.setPosition(x - ERROR_BUTTON_GAP - exportW, y);
		}
	}

	/** 将错误详情导出为文本文件（游戏运行目录 error-reports/ 下）。 */
	private void exportErrorDetails() {
		if (this.errorLayout == null) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		this.errorLayout.export(sb);
		String stamp = LocalDateTime.now().format(STAMP);
		Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("error-reports");
		Path file = dir.resolve("gui-error-" + stamp + ".txt");
		try {
			Files.createDirectories(dir);
			Files.writeString(file, sb.toString());
			LOGGER.info("错误详情已导出：{}", file.toAbsolutePath());
		} catch (IOException e) {
			LOGGER.error("导出错误详情失败", e);
		}
	}

	/** 错误隔离态渲染：错误详情 + 操作按钮（业务布局不再渲染）。 */
	private void renderErrorState(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);
		if (this.errorLayout != null) {
			try {
				this.errorLayout.render(ctx, mouseX, mouseY, delta);
			} catch (Throwable t) {
				LOGGER.error("错误详情布局渲染失败", t);
			}
		}
		if (this.exportButton != null) {
			this.exportButton.render(ctx, mouseX, mouseY, delta);
		}
		if (this.closeButton != null) {
			this.closeButton.render(ctx, mouseX, mouseY, delta);
		}
	}

	/** 错误隔离态鼠标点击：仅响应导出 / 关闭按钮，业务布局与业务按钮不再响应。 */
	private boolean handleErrorStateClick(double mouseX, double mouseY, int button) {
		if (this.exportButton != null && this.exportButton.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (this.closeButton != null && this.closeButton.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		return false;
	}

	// ---- 生命周期 ----

	@Override
	protected void init() {
		super.init();
		if (this.fatalError != null) {
			// 错误隔离态：仅重排错误界面（resize 重入时保留已展示的错误）
			this.layoutErrorState();
			return;
		}
		if (this.root != null) {
			try {
				this.root.setBounds(0, 0, this.width, this.height);
				this.root.init();
				this.root.layout();
			} catch (Throwable t) {
				this.onLayoutError(t);
			}
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		if (this.fatalError != null) {
			this.renderErrorState(ctx, mouseX, mouseY, delta);
			// 错误隔离态同样保留 toast 渲染（消息通知与错误展示不冲突）
			ToastQueue.render(ctx, mouseX, mouseY);
			return;
		}
		this.renderBackground(ctx);
		if (this.root != null) {
			try {
				this.root.render(ctx, mouseX, mouseY, delta);
			} catch (Throwable t) {
				this.onLayoutError(t);
			}
		}
		Tooltip.renderAll(ctx);
		ToastQueue.render(ctx, mouseX, mouseY);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.fatalError != null) {
			return;
		}
		if (this.root != null) {
			this.root.tick();
		}
	}

	// ---- 事件转发（错误隔离态时仅响应错误界面） ----

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.fatalError != null) {
			return this.handleErrorStateClick(mouseX, mouseY, button);
		}
		try {
			if (this.root != null && this.root.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
		} catch (Throwable t) {
			this.onLayoutError(t);
			return true;
		}
		// 布局树未消费的点击：命中 toast 区域则关闭该条（只消费自身区域，不阻断业务）
		if (ToastQueue.mouseClicked(mouseX, mouseY)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (this.fatalError != null) {
			return false;
		}
		try {
			if (this.root != null && this.root.mouseReleased(mouseX, mouseY, button)) {
				return true;
			}
		} catch (Throwable t) {
			this.onLayoutError(t);
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (this.fatalError != null) {
			return false;
		}
		try {
			if (this.root != null && this.root.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
				return true;
			}
		} catch (Throwable t) {
			this.onLayoutError(t);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (this.fatalError != null) {
			// 错误详情支持滚动查看
			return this.errorLayout != null && this.errorLayout.mouseScrolled(mouseX, mouseY, amount);
		}
		try {
			if (this.root != null && this.root.mouseScrolled(mouseX, mouseY, amount)) {
				return true;
			}
		} catch (Throwable t) {
			this.onLayoutError(t);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.fatalError != null) {
			// 保留 ESC 关闭等默认行为
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		try {
			if (this.root != null && this.root.keyPressed(keyCode, scanCode, modifiers)) {
				return true;
			}
		} catch (Throwable t) {
			this.onLayoutError(t);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int keyCode) {
		if (this.fatalError != null) {
			return super.charTyped(chr, keyCode);
		}
		try {
			if (this.root != null && this.root.charTyped(chr, keyCode)) {
				return true;
			}
		} catch (Throwable t) {
			this.onLayoutError(t);
			return true;
		}
		return super.charTyped(chr, keyCode);
	}
}

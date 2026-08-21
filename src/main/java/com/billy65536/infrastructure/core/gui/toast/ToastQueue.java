package com.billy65536.infrastructure.core.gui.toast;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 客户端 toast 通知队列（HUD 自研实现，无外部依赖）。
 *
 * <p>静态单例：{@link #enqueue} 入队（最多同时显示 {@link #MAX_TOASTS} 条，超出挤掉最旧的），
 * 由全局客户端 tick 驱动 {@link #tick()} 倒计时，超时自动出队；{@link #render} 在屏幕右上角
 * 从上往下堆叠渲染（深色半透明背景 + 类型色左边条 + 白色正文）；{@link #mouseClicked} 支持
 * 点击 toast 立即关闭。</p>
 *
 * <p>渲染挂点双轨互斥：ScreenContainer 打开时由屏幕自身 render 调用本类渲染；
 * 无 GUI 时由 HudRenderCallback 兜底调用，两处不会同时触发（见 {@code Messenger} 路由）。</p>
 *
 * <p>入队即写日志：按 {@link ToastType} 的日志级别记录消息本体，调用方无需再自行记录，
 * 避免重复留痕。</p>
 */
public final class ToastQueue {

	/** 同时显示的最大条数。 */
	public static final int MAX_TOASTS = 3;
	/** 单条存活时长（tick，20 tick = 1 秒）：4 秒。 */
	private static final int DURATION_TICKS = 80;
	/** 距屏幕右边距。 */
	private static final int MARGIN_RIGHT = 8;
	/** 距屏幕顶边距。 */
	private static final int MARGIN_TOP = 8;
	/** 条与条之间的垂直间距。 */
	private static final int GAP = 4;
	/** 单条高度。 */
	private static final int HEIGHT = 20;
	/** 类型色竖条宽度。 */
	private static final int BAR_WIDTH = 3;
	/** 正文距竖条右侧间距。 */
	private static final int TEXT_PADDING = 6;
	/** 单条最大宽度（文本超长时由 TextRenderer 截断显示）。 */
	private static final int MAX_WIDTH = 200;
	/** 背景：深色半透明。 */
	private static final int BG_COLOR = 0xCC000000;
	/** 正文颜色：纯白。 */
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	private static final Logger LOGGER = LoggerFactory.getLogger(ToastQueue.class);

	/** 队列头部为最新一条（渲染在最上方）。 */
	private static final Deque<Toast> QUEUE = new ArrayDeque<>();

	private ToastQueue() {
	}

	/**
	 * 入队一条 toast 通知，并按其类型写日志。
	 *
	 * @param message 消息文本
	 * @param type    消息类型（决定左边条颜色与日志级别）
	 */
	public static void enqueue(Text message, ToastType type) {
		QUEUE.addFirst(new Toast(message, type));
		while (QUEUE.size() > MAX_TOASTS) {
			QUEUE.removeLast();
		}
		log(type, message.getString());
	}

	/**
	 * 按 {@link ToastType} 日志级别写日志，仅在入队时调用一次
	 * （消息本体与 toast 对象分离，避免同一消息在聊天与 toast 两条路径重复留痕）。
	 */
	private static void log(ToastType type, String message) {
		switch (type.logLevel()) {
			case ERROR -> LOGGER.error("[toast] {}", message);
			case WARN -> LOGGER.warn("[toast] {}", message);
			default -> LOGGER.info("[toast] {}", message);
		}
	}

	/**
	 * 队列 tick 驱动：逐条递减剩余时长，超时出队。由全局 {@code ClientTickEvents.END_CLIENT_TICK} 调用。
	 */
	public static void tick() {
		QUEUE.removeIf(t -> --t.remainingTicks <= 0);
	}

	/**
	 * 渲染所有 toast（右上角堆叠）。GUI 打开时由 ScreenContainer.render 调用，
	 * 无 GUI 时由 HudRenderCallback 调用（两处互斥，见 {@code Messenger}）。
	 *
	 * @param ctx 绘制上下文（GUI 逻辑坐标）
	 */
	public static void render(DrawContext ctx) {
		if (QUEUE.isEmpty()) {
			return;
		}
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		int screenWidth = ctx.getScaledWindowWidth();
		int y = MARGIN_TOP;
		for (Toast toast : QUEUE) {
			int width = widthOf(tr, toast);
			int x = screenWidth - MARGIN_RIGHT - width;
			ctx.fill(x, y, x + width, y + HEIGHT, BG_COLOR);
			ctx.fill(x, y, x + BAR_WIDTH, y + HEIGHT, toast.type.accentColor());
			ctx.drawText(tr, toast.message.getString(), x + BAR_WIDTH + TEXT_PADDING,
					y + (HEIGHT - tr.fontHeight) / 2, TEXT_COLOR, true);
			y += HEIGHT + GAP;
		}
	}

	/**
	 * 点击命中检测：命中任意一条 toast 即关闭该条并返回 true，否则 false。
	 * 应在业务布局事件分发之后调用（toast 只消费自身区域内的点击，不阻断业务点击）。
	 *
	 * @param mouseX 鼠标逻辑坐标 X
	 * @param mouseY 鼠标逻辑坐标 Y
	 */
	public static boolean mouseClicked(double mouseX, double mouseY) {
		if (QUEUE.isEmpty()) {
			return false;
		}
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
		int y = MARGIN_TOP;
		for (Toast toast : QUEUE) {
			int width = widthOf(tr, toast);
			int x = screenWidth - MARGIN_RIGHT - width;
			if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + HEIGHT) {
				QUEUE.remove(toast);
				return true;
			}
			y += HEIGHT + GAP;
		}
		return false;
	}

	/** 计算单条 toast 宽度（文本宽度 + 竖条 + 双侧内边距，上限 {@link #MAX_WIDTH}）。 */
	private static int widthOf(TextRenderer tr, Toast toast) {
		return Math.min(MAX_WIDTH, tr.getWidth(toast.message) + BAR_WIDTH + TEXT_PADDING * 2);
	}

	/** 单条 toast 数据（可变剩余时长，由 tick 驱动递减）。 */
	private static final class Toast {

		final Text message;
		final ToastType type;
		int remainingTicks;

		Toast(Text message, ToastType type) {
			this.message = message;
			this.type = type;
			this.remainingTicks = DURATION_TICKS;
		}
	}
}

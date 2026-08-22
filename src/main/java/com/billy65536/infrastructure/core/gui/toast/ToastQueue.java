package com.billy65536.infrastructure.core.gui.toast;

import com.billy65536.infrastructure.core.config.InfrastructureConfigLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * 客户端 toast 通知队列（HUD 自研实现，无外部依赖）。
 *
 * <p>静态单例：{@link #enqueue} 入队（同时显示条数受配置
 * {@code infrastructure:config/gui.toast.maxToasts} 限制，默认 0 = 无限制），
 * 由全局客户端 tick 驱动 {@link #tick()} 倒计时，超时自动出队；{@link #render} 在屏幕
 * <b>左上角</b>从上往下堆叠渲染（深色背景默认 20% 透明度，可经配置
 * {@code infrastructure:config/gui.toast.bgOpacityPercent} 调整 + 类型色左边条 + 白色正文），
 * 文本按 {@code \n} 拆行、超宽自动换行，完整渲染不截断；入场淡入 + 左侧滑入、到期前淡出
 * （快速档 4 tick），鼠标悬浮时背景在常态基础上加亮 40% 并显示 1px 全周类型色边框；
 * {@link #mouseClicked} 支持点击 toast 立即关闭（无动画）。</p>
 *
 * <p>「同批」消息（一次逻辑操作内连续发送的多条）经 {@link #enqueueAll} 批量入队，
 * <b>不受条数上限挤除</b>（靠倒计时自然消失）；单条 {@link #enqueue} 仍按上限挤除最旧。</p>
 *
 * <p>渲染挂点双轨互斥：ScreenContainer 打开时由屏幕自身 render 调用本类渲染；
 * 无 GUI 时由 HudRenderCallback 兜底调用；其他 GUI（背包/暂停/聊天等非 ScreenContainer）打开时
 * <b>不渲染</b>——1.20.1 中 HUD 回调在任意 GUI 打开时仍会触发，但 Screen 绘制在 HUD 之后，
 * 若此时渲染会被该 GUI 背景覆盖（见 {@code Messenger} 路由与 {@code InfrastructureMod} 挂点注释）。</p>
 *
 * <p>入队即写日志：按 {@link ToastType} 的日志级别记录消息本体，调用方无需再自行记录，
 * 避免重复留痕。</p>
 */
public final class ToastQueue {

	/** 距屏幕左边距（左上角定位）。 */
	private static final int MARGIN_LEFT = 8;
	/** 距屏幕顶边距。 */
	private static final int MARGIN_TOP = 8;
	/** 条与条之间的垂直间距。 */
	private static final int GAP = 4;
	/** 类型色竖条宽度。 */
	private static final int BAR_WIDTH = 3;
	/** 正文距竖条右侧间距。 */
	private static final int TEXT_PADDING = 6;
	/** 行与行之间的垂直间距。 */
	private static final int LINE_GAP = 2;
	/** 正文上下内边距。 */
	private static final int VERTICAL_PADDING = 4;
	/** 背景 RGB：纯黑（alpha 由配置 gui.toast.bgOpacityPercent 决定，默认 20%）。 */
	private static final int BG_RGB = 0x000000;
	/** 正文颜色：纯白。 */
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	/** 淡入/淡出过渡时长（tick，1/20 秒）。 */
	private static final int FADE_TICKS = 4;
	/** 左侧滑入过渡时长（tick）。 */
	private static final int SLIDE_TICKS = 4;
	/** 悬浮时背景加亮增量：常态基础上 +40%（255 × 40% ≈ 0x66），封顶 255。 */
	private static final int HOVER_BG_BOOST = 0x66;

	private static final Logger LOGGER = LoggerFactory.getLogger(ToastQueue.class);

	/** 队列头部为最新一条（渲染在最上方）。 */
	private static final Deque<Toast> QUEUE = new ArrayDeque<>();

	private ToastQueue() {
	}

	/**
	 * 入队一条 toast 通知，并按其类型写日志。
	 *
	 * <p>同时显示条数受配置 {@code gui.toast.maxToasts} 限制（0 = 无限制）；
	 * 超限时挤掉最旧的。</p>
	 *
	 * @param message 消息文本
	 * @param type    消息类型（决定左边条颜色与日志级别）
	 */
	public static void enqueue(Text message, ToastType type) {
		QUEUE.addFirst(new Toast(message, type));
		int maxToasts = InfrastructureConfigLoader.get().gui.toast.maxToasts;
		if (maxToasts > 0) {
			while (QUEUE.size() > maxToasts) {
				QUEUE.removeLast();
			}
		}
		log(type, message.getString());
	}

	/**
	 * 同批批量入队：一次逻辑操作内连续发送的多条消息全部保留，
	 * <b>不受条数上限挤除</b>（靠各自倒计时自然消失），并逐条按类型写日志。
	 *
	 * @param messages 同批消息列表（保持给定顺序，首条渲染在最上方）
	 * @param type     消息类型
	 */
	public static void enqueueAll(Collection<Text> messages, ToastType type) {
		for (Text message : messages) {
			QUEUE.addFirst(new Toast(message, type));
			log(type, message.getString());
		}
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
	 * 队列 tick 驱动：逐条递增存活时长并递减剩余时长，超时出队。
	 * 由全局 {@code ClientTickEvents.END_CLIENT_TICK} 调用。
	 */
	public static void tick() {
		QUEUE.removeIf(t -> {
			t.age++;
			return --t.remainingTicks <= 0;
		});
	}

	/**
	 * 渲染所有 toast（左上角堆叠）。GUI 打开时由 ScreenContainer.render 调用，
	 * 无 GUI 时由 HudRenderCallback 调用（两处互斥，见 {@code Messenger}）。
	 *
	 * <p>动画：入队后 {@link #FADE_TICKS} tick 淡入 + {@link #SLIDE_TICKS} tick 左侧
	 * 滑入（easeOutCubic），到期前 {@link #FADE_TICKS} tick 淡出；点击关闭无动画。
	 * 悬浮：鼠标命中时背景在常态基础上加亮 +40%（{@link #HOVER_BG_BOOST}）并绘制
	 * 1px 全周类型色边框。</p>
	 *
	 * @param ctx    绘制上下文（GUI 逻辑坐标）
	 * @param mouseX 鼠标逻辑坐标 X（由调用方换算：物理像素 × scaledWidth / width）
	 * @param mouseY 鼠标逻辑坐标 Y（同上）
	 */
	public static void render(DrawContext ctx, double mouseX, double mouseY) {
		if (QUEUE.isEmpty()) {
			return;
		}
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		int maxWidth = maxWidthOf(ctx.getScaledWindowWidth());
		int y = MARGIN_TOP;
		for (Toast toast : QUEUE) {
			Layout layout = layout(tr, toast, maxWidth, y);
			int x = layout.x - slideOffset(toast, layout.width);
			int alpha = alphaOf(toast);
			boolean hover = mouseX >= x && mouseX <= x + layout.width
					&& mouseY >= layout.y && mouseY <= layout.y + layout.height;
			// 背景：常态 alpha 取配置百分比，hover 时在此基础上加亮 +40%，再随整体透明度淡入淡出。
			int normalBgAlpha = bgOpacityAlpha();
			int bgAlpha = (int) ((hover ? Math.min(255, normalBgAlpha + HOVER_BG_BOOST) : normalBgAlpha)
					* alpha / 255f + 0.5f);
			ctx.fill(x, layout.y, x + layout.width, layout.y + layout.height, withAlpha(BG_RGB, bgAlpha));
			// 悬浮边框：1px 全周类型色（与左边条呼应）。
			if (hover) {
				int border = withAlpha(toast.type.accentColor(), alpha);
				ctx.fill(x, layout.y, x + layout.width, layout.y + 1, border);
				ctx.fill(x, layout.y + layout.height - 1, x + layout.width, layout.y + layout.height, border);
				ctx.fill(x, layout.y + 1, x + 1, layout.y + layout.height - 1, border);
				ctx.fill(x + layout.width - 1, layout.y + 1, x + layout.width, layout.y + layout.height - 1, border);
			}
			// 类型色左边条（随整体透明度淡入淡出）。
			ctx.fill(x, layout.y, x + BAR_WIDTH, layout.y + layout.height, withAlpha(toast.type.accentColor(), alpha));
			// 正文（带阴影，随整体透明度淡入淡出）。
			int textY = layout.y + VERTICAL_PADDING;
			for (OrderedText line : layout.lines) {
				ctx.drawText(tr, line, x + BAR_WIDTH + TEXT_PADDING, textY, withAlpha(TEXT_COLOR, alpha), true);
				textY += tr.fontHeight + LINE_GAP;
			}
			y += layout.height + GAP;
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
		int maxWidth = maxWidthOf(MinecraftClient.getInstance().getWindow().getScaledWidth());
		int y = MARGIN_TOP;
		for (Toast toast : QUEUE) {
			Layout layout = layout(tr, toast, maxWidth, y);
			// 命中矩形与 render 一致（含滑入偏移），避免动画期间点击错位。
			int x = layout.x - slideOffset(toast, layout.width);
			if (mouseX >= x && mouseX <= x + layout.width
					&& mouseY >= layout.y && mouseY <= layout.y + layout.height) {
				QUEUE.remove(toast);
				return true;
			}
			y += layout.height + GAP;
		}
		return false;
	}

	/** 当前配置的常态背景 alpha（0-255）：百分比 × 255 / 100。 */
	private static int bgOpacityAlpha() {
		return InfrastructureConfigLoader.get().gui.toast.bgOpacityPercent * 255 / 100;
	}

	/** 当前配置下的单条最大宽度（屏幕逻辑宽度 × maxWidthPercent / 100）。 */
	private static int maxWidthOf(int screenWidth) {
		int percent = InfrastructureConfigLoader.get().gui.toast.maxWidthPercent;
		return Math.max(1, screenWidth * percent / 100);
	}

	/**
	 * 文本按 {@code \n} 拆段，每段再按 {@code maxTextWidth} 自动换行，
	 * 保证完整渲染不截断。返回 {@link OrderedText} 行以保留原样式（含格式码）。
	 */
	private static List<OrderedText> wrappedLines(TextRenderer tr, Text message, int maxTextWidth) {
		List<OrderedText> lines = new ArrayList<>();
		for (String segment : message.getString().split("\n", -1)) {
			// 1.20.1 的 TextRenderer 无 getWrappedLines；用 public wrapLines(StringVisitable, int)
			lines.addAll(tr.wrapLines(Text.literal(segment), Math.max(1, maxTextWidth)));
		}
		return lines;
	}

	/**
	 * 计算单条 toast 的布局（render 与 mouseClicked 共用，消除 WET）：
	 * 左上角定位，宽度自适应（上限 maxWidth），高度随换行行数动态增长。
	 */
	private static Layout layout(TextRenderer tr, Toast toast, int maxWidth, int topY) {
		int maxTextWidth = maxWidth - BAR_WIDTH - TEXT_PADDING * 2;
		List<OrderedText> lines = wrappedLines(tr, toast.message, maxTextWidth);
		int longest = 0;
		for (OrderedText line : lines) {
			longest = Math.max(longest, tr.getWidth(line));
		}
		int width = Math.min(maxWidth, longest + BAR_WIDTH + TEXT_PADDING * 2);
		int height = lines.size() * tr.fontHeight
				+ Math.max(0, lines.size() - 1) * LINE_GAP
				+ VERTICAL_PADDING * 2;
		return new Layout(MARGIN_LEFT, topY, width, height, lines);
	}

	/** 单条 toast 的布局结果（坐标 + 尺寸 + 换行后的行列表）。 */
	private record Layout(int x, int y, int width, int height, List<OrderedText> lines) {
	}

	/** 单条 toast 数据（可变存活时长与剩余时长，由 tick 驱动同步增减）。 */
	private static final class Toast {

		final Text message;
		final ToastType type;
		/** 自入队起经过的 tick 数（驱动淡入/滑入动画）。 */
		int age;
		int remainingTicks;

		Toast(Text message, ToastType type) {
			this.message = message;
			this.type = type;
			// 入队时现取配置，已入队条目不刷新时长。
			this.remainingTicks = InfrastructureConfigLoader.get().gui.toast.durationTicks;
		}
	}

	/**
	 * 整体透明度（0..255）：取淡入与淡出进度的较小者（双重限制），
	 * 淡入 = 存活时长 / FADE_TICKS，淡出 = 剩余时长 / FADE_TICKS。
	 */
	private static int alphaOf(Toast toast) {
		float fadeIn = Math.min(1f, (float) toast.age / FADE_TICKS);
		float fadeOut = Math.min(1f, (float) toast.remainingTicks / FADE_TICKS);
		return (int) (Math.min(fadeIn, fadeOut) * 255f + 0.5f);
	}

	/** 左侧滑入的水平偏移量（像素）：动画期间从屏外滑入到位，动画结束为 0。 */
	private static int slideOffset(Toast toast, int width) {
		float t = Math.min(1f, (float) toast.age / SLIDE_TICKS);
		return (int) ((1f - easeOutCubic(t)) * (width + MARGIN_LEFT));
	}

	/** easeOutCubic 缓动：t∈[0,1]，先快后慢，末端平滑停止。 */
	private static float easeOutCubic(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	/** 替换 ARGB 颜色的 alpha 通道（保留 RGB）。 */
	private static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
	}
}

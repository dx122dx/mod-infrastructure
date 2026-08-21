package com.billy65536.infrastructure.core.gui.toast;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组消息统一门面：内部按当前界面状态自动路由到「聊天栏」或「Toast + 日志」。
 *
 * <p>路由规则：</p>
 * <ul>
 *   <li>当前屏幕为基础设施 {@link ScreenContainer}（自身 GUI 打开）→ 弹 toast 并写日志文件，
 *       聊天区保持干净；</li>
 *   <li>否则（游戏内 / 聊天屏 / 其他模组界面）→ 聊天栏 {@code sendMessage}（聊天默认计入日志），
 *       行为与原先直发聊天完全一致；</li>
 *   <li>玩家未进世界（player 为 null）→ 仅写日志，不弹不发，避免崩溃。</li>
 * </ul>
 *
 * <p><b>路由判定必须用 {@code instanceof ScreenContainer}</b>：命令在聊天框输入时
 * {@code currentScreen} 是 ChatScreen（非 null），若按 {@code != null} 判定会误判为 toast，
 * 且 ChatScreen 没有 toast 渲染挂点，消息会直接丢失。</p>
 *
 * <p>仅提供 {@code info/warn/error} 三个简略方法与全量 {@link #notify(Text, ToastType)}：
 * 成功类反馈建议显式传 {@link ToastType#SUCCESS}，常规信息用 {@link #info(Text)}。</p>
 */
public final class Messenger {

	private static final Logger LOGGER = LoggerFactory.getLogger(Messenger.class);

	private Messenger() {
	}

	/**
	 * 发送一条消息（自动路由）。
	 *
	 * @param message 消息文本
	 * @param type    消息类型（仅 Toast 场景生效：决定左边条颜色与日志级别；聊天与兜底路径不使用）
	 */
	public static void notify(Text message, ToastType type) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.currentScreen instanceof ScreenContainer) {
			ToastQueue.enqueue(message, type);
		} else if (client.player != null) {
			client.player.sendMessage(message, false);
		} else {
			LOGGER.info("[msg] {}", message.getString());
		}
	}

	/** 常规信息。 */
	public static void info(Text message) {
		notify(message, ToastType.INFO);
	}

	/** 告警信息。 */
	public static void warn(Text message) {
		notify(message, ToastType.WARN);
	}

	/** 错误信息。 */
	public static void error(Text message) {
		notify(message, ToastType.ERROR);
	}
}

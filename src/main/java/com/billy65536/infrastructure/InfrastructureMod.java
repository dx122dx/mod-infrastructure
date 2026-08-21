package com.billy65536.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.toast.ToastQueue;
import com.billy65536.infrastructure.core.module.ModuleRegistry;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * 模组入口。mod id 为 {@code infrastructure}，中文名「Billy's Mod infrastructure」。
 *
 * <p>本模组是通用客户端基础设施，按子模块组织（目前仅有 debugger 调试子模块）。
 * 模块私有的初始化步骤一律下沉到模块自身，本类不感知任何具体子模块。</p>
 *
 * <h2>初始化时序</h2>
 *
 * <p>本类的 {@link #onInitializeClient()} 共做四件事：登记 {@code /inf} 命令回调、
 * 把模块发现挂到 {@link ClientLifecycleEvents#CLIENT_STARTED} 上、
 * 注册 {@link HudRenderCallback} 兜底渲染 toast、注册 {@link ClientTickEvents} 驱动 toast 倒计时。</p>
 *
 * <p>模块发现之所以<b>不</b>在入口点内直接执行：Fabric 按依赖拓扑序调用各模组的
 * {@code client} 入口点，依赖方（infrastructure）必然早于依赖它的模组（如 chunkscanner）。
 * 若此时就 SPI 实例化并初始化下游模块，模块会在其宿主模组自身
 * {@code onInitializeClient} 之前被初始化，读到未就绪的宿主状态。
 * 改挂 {@code CLIENT_STARTED} 后，发现时机严格晚于<b>所有</b>模组的客户端入口点，
 * 模块可安全依赖宿主模组的初始化结果。</p>
 *
 * <p>命令回调可以先注册：Brigadier 建树发生在回调被触发时（进入世界），
 * 那时模块早已登记完毕；回调内另有一次幂等的 {@code discover()} 兜底。</p>
 */
public final class InfrastructureMod implements ClientModInitializer {

    public static final String MOD_ID = "infrastructure";
    public static final String NAME = "Billy's Mod infrastructure";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        // 命令回调只在建树时读取注册表，可先于模块发现登记
        InfrastructureCommands.register();
        // 模块发现推迟到所有模组的客户端入口点执行完毕之后
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> ModuleRegistry.discover());

        // toast 通知：无 GUI 时由 HUD 回调兜底渲染（ScreenContainer 打开时由屏幕自身渲染，此处跳过避免双画）
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> {
            if (!(MinecraftClient.getInstance().currentScreen instanceof ScreenContainer)) {
                ToastQueue.render(ctx);
            }
        });
        // toast 倒计时统一驱动
        ClientTickEvents.END_CLIENT_TICK.register(client -> ToastQueue.tick());
    }
}

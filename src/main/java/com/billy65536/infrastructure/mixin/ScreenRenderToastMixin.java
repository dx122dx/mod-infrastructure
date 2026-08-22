package com.billy65536.infrastructure.mixin;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.toast.ToastQueue;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 将 toast 渲染时机收敛到 {@link Screen#renderWithTooltip} 的 TAIL。
 * <p>
 * 背景：qab 等下游使用方在 {@code ScreenContainer.render()}（super 调用，toast 已在其中画完）
 * 之后追加绘制标题、分隔线、设置行等内容，导致这些 GUI 内容盖住 toast。
 * 而 {@code renderWithTooltip} 的 TAIL 处于整条渲染链（子类追加内容 + tooltip）完成之后，
 * 此时渲染 toast 必然处于最上层，且下游零改动、无感知。
 * <p>
 * 注入点安全性（已实证）：{@code SpruceScreen} 未 override {@code renderWithTooltip}，
 * ScreenContainer 及其所有子类均走 {@code Screen} 基类实现，本注入必定触发；
 * 非 ScreenContainer 的 GUI 通过 {@code instanceof} 过滤，保持原行为不变。
 */
@Mixin(Screen.class)
public abstract class ScreenRenderToastMixin {

    @Inject(method = "renderWithTooltip", at = @At("TAIL"))
    private void renderToastAfterScreenContents(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof ScreenContainer) {
            ToastQueue.render(context, mouseX, mouseY);
        }
    }
}

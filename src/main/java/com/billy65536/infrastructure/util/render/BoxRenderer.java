package com.billy65536.infrastructure.util.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.MatrixStack.Entry;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 世界内线框盒渲染器。
 * <p>
 * 提供一次性调用的静态方法 {@link #render(WorldRenderContext, List)}：
 * 内部完成视图/投影矩阵设置、RenderSystem 状态管理与线框盒顶点绘制，并在 finally 中恢复
 * 所有被修改的状态（矩阵栈、混合、深度、线宽、着色器）。调用方只需在
 * {@code WorldRenderEvents.LAST} 中收集 {@link Box} 列表并调用本方法。
 * </p>
 * <p>
 * 说明：当前实现基于固定管线（{@code RenderSystem} + DEBUG_LINES），顶点使用世界坐标，
 * 视图矩阵由 {@code setupMatrices} 通过 JOML {@code lookAt} 构建，确保与 Minecraft 坐标系兼容。
 * </p>
 */
public final class BoxRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("infrastructure.core.render.BoxRenderer");

    private BoxRenderer() {
    }

    /**
     * 渲染一组线框盒。
     * <p>
     * 空列表直接返回；渲染过程中任何异常都会被捕获记录，且所有 RenderSystem 状态
     * 都会在 finally 中恢复，不会污染后续渲染。
     * </p>
     *
     * @param context 世界渲染上下文（来自 {@code WorldRenderEvents.LAST}）
     * @param boxes   待渲染的线框盒列表
     */
    public static void render(WorldRenderContext context, List<Box> boxes) {
        if (boxes == null || boxes.isEmpty()) return;

        try {
            setupMatrices(context);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.lineWidth(2.0f);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

            for (Box box : boxes) {
                drawWireframeBox(buffer, box);
            }

            tessellator.draw();
        } catch (Exception e) {
            LOGGER.error("Failed to render wireframe boxes: {}", e.getMessage(), e);
        } finally {
            RenderSystem.lineWidth(1.0f);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            restoreMatrices();
        }
    }

    // ==================== 矩阵管理 ====================

    /**
     * 使用 JOML 的 lookAt 构建标准视图矩阵，确保与 Minecraft 坐标系兼容，
     * 并写入 RenderSystem model-view 栈（push 后覆盖）。
     */
    private static void setupMatrices(WorldRenderContext context) {
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        float yaw = camera.getYaw();
        float pitch = camera.getPitch();

        // Minecraft 坐标系计算前方向量：
        // yaw=0 朝 +Z(南), yaw=90 朝 -X(西), pitch>0 朝下
        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = pitch * MathHelper.RADIANS_PER_DEGREE;
        float fx = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad);
        float fy = -MathHelper.sin(pitchRad);
        float fz = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad);

        // 用 lookAt 构建视图矩阵
        float cx = (float) camPos.x;
        float cy = (float) camPos.y;
        float cz = (float) camPos.z;
        Matrix4f viewMatrix = new Matrix4f().lookAt(
                cx, cy, cz,
                cx + fx, cy + fy, cz + fz,
                0f, 1f, 0f
        );

        // 设置投影矩阵
        Matrix4f projectionMatrix = context.projectionMatrix();
        if (projectionMatrix != null) {
            RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorter.BY_DISTANCE);
        }

        // 写入 RenderSystem model-view 栈
        MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        Entry top = modelViewStack.peek();
        if (top != null) {
            top.getPositionMatrix().set(viewMatrix);
        }
        RenderSystem.applyModelViewMatrix();
    }

    private static void restoreMatrices() {
        RenderSystem.getModelViewStack().pop();
        RenderSystem.applyModelViewMatrix();
    }

    // ==================== 线框盒绘制 ====================

    private static void v(BufferBuilder buffer, double x, double y, double z, int r, int g, int b, int a) {
        buffer.vertex(x, y, z).color(r, g, b, a).next();
    }

    private static void drawWireframeBox(BufferBuilder buffer, Box box) {
        int argb = box.argb();
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >> 24) & 0xFF;

        double margin = -0.001; // 微偏移避免 z-fighting
        double x1 = box.minX() - margin, y1 = box.minY() - margin, z1 = box.minZ() - margin;
        double x2 = box.maxX() + margin, y2 = box.maxY() + margin, z2 = box.maxZ() + margin;

        v(buffer, x1, y1, z1, r, g, b, a); v(buffer, x2, y1, z1, r, g, b, a);
        v(buffer, x2, y1, z1, r, g, b, a); v(buffer, x2, y1, z2, r, g, b, a);
        v(buffer, x2, y1, z2, r, g, b, a); v(buffer, x1, y1, z2, r, g, b, a);
        v(buffer, x1, y1, z2, r, g, b, a); v(buffer, x1, y1, z1, r, g, b, a);

        v(buffer, x1, y2, z1, r, g, b, a); v(buffer, x2, y2, z1, r, g, b, a);
        v(buffer, x2, y2, z1, r, g, b, a); v(buffer, x2, y2, z2, r, g, b, a);
        v(buffer, x2, y2, z2, r, g, b, a); v(buffer, x1, y2, z2, r, g, b, a);
        v(buffer, x1, y2, z2, r, g, b, a); v(buffer, x1, y2, z1, r, g, b, a);

        v(buffer, x1, y1, z1, r, g, b, a); v(buffer, x1, y2, z1, r, g, b, a);
        v(buffer, x2, y1, z1, r, g, b, a); v(buffer, x2, y2, z1, r, g, b, a);
        v(buffer, x2, y1, z2, r, g, b, a); v(buffer, x2, y2, z2, r, g, b, a);
        v(buffer, x1, y1, z2, r, g, b, a); v(buffer, x1, y2, z2, r, g, b, a);
    }
}

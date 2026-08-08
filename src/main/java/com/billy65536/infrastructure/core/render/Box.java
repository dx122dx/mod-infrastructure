package com.billy65536.infrastructure.core.render;

/**
 * 轴对齐包围盒 + 颜色，供 {@link BoxRenderer} 绘制线框高亮。
 * <p>
 * 坐标为世界坐标（方块坐标的 double 值），颜色为 ARGB 格式（{@code 0xAARRGGBB}）。
 * </p>
 *
 * @param minX 最小 X（包含）
 * @param minY 最小 Y（包含）
 * @param minZ 最小 Z（包含）
 * @param maxX 最大 X（不包含，即渲染范围覆盖 [minX, maxX)）
 * @param maxY 最大 Y（不包含）
 * @param maxZ 最大 Z（不包含）
 * @param argb ARGB 颜色
 */
public record Box(double minX, double minY, double minZ,
                  double maxX, double maxY, double maxZ,
                  int argb) {

    /**
     * 由世界坐标构造包围盒。
     *
     * @param minX 最小 X（包含）
     * @param minY 最小 Y（包含）
     * @param minZ 最小 Z（包含）
     * @param maxX 最大 X（不包含）
     * @param maxY 最大 Y（不包含）
     * @param maxZ 最大 Z（不包含）
     * @param argb ARGB 颜色
     * @return Box 实例
     */
    public static Box of(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ,
                         int argb) {
        return new Box(minX, minY, minZ, maxX, maxY, maxZ, argb);
    }

    /**
     * 由整数方块坐标构造包围盒。
     * <p>
     * 参数表示一组包含 {@code min..max} 的方块范围，渲染时扩展为 {@code [min, max+1)}，
     * 使线框恰好框住整组方块。
     * </p>
     *
     * @param minX 最小方块 X（包含）
     * @param minY 最小方块 Y（包含）
     * @param minZ 最小方块 Z（包含）
     * @param maxX 最大方块 X（包含）
     * @param maxY 最大方块 Y（包含）
     * @param maxZ 最大方块 Z（包含）
     * @param argb ARGB 颜色
     * @return Box 实例
     */
    public static Box ofBlocks(int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ,
                               int argb) {
        return new Box(minX, minY, minZ,
                maxX + 1.0, maxY + 1.0, maxZ + 1.0,
                argb);
    }
}

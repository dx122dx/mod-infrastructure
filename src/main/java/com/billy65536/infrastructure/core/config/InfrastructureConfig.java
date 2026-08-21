package com.billy65536.infrastructure.core.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * infrastructure 主模块固定配置（AutoConfig 模型），挂载为 {@code infrastructure:config} 段。
 *
 * <p>配置路径为 {@code infrastructure:config/gui.toast.<字段>}，经
 * {@link ConfigAccessor} 的嵌套点分路径索引，{@code /inf config get|set|reset|gui} 可直接访问
 * （如 {@code /inf config set infrastructure:config/gui.toast.maxWidthPercent 50}）。</p>
 *
 * <p>结构约定：字段按 {@code gui} → {@code toast} 两级嵌套组织（AutoConfig 配置类内
 * <b>严禁 static 字段</b>，默认值直接内联为实例字段初始化器；嵌套对象用
 * {@link ConfigEntry.Gui.CollapsibleObject} 标注并在 GUI 中呈现为可折叠子类目）。</p>
 */
@Config(name = "infrastructure-config")
public class InfrastructureConfig implements ConfigData {

    /** GUI 相关配置分组。 */
    @ConfigEntry.Gui.CollapsibleObject
    public Gui gui = new Gui();

    /** GUI 分组（嵌套对象，字段路径前缀 {@code gui.}）。 */
    public static class Gui {

        /** Toast 通知配置分组。 */
        @ConfigEntry.Gui.CollapsibleObject
        public Toast toast = new Toast();

        /** Toast 分组（嵌套对象，字段路径前缀 {@code gui.toast.}）。 */
        public static class Toast {

            /**
             * 单条 toast 最大宽度 = 屏幕逻辑宽度 × percent/100。
             *
             * <p>超过该宽度时文本自动换行；默认 40（≈342px @ 854px 屏幕）。
             * GUI 中以滑块呈现（10–80），此处为双保险校验。</p>
             */
            @ConfigEntry.Gui.Tooltip
            @ConfigEntry.BoundedDiscrete(min = 10, max = 80)
            public int maxWidthPercent = 40;

            /** 单条 toast 显示时长（tick）。默认 120（6 秒）。 */
            @ConfigEntry.Gui.Tooltip
            @ConfigEntry.BoundedDiscrete(min = 20, max = 600)
            public int durationTicks = 120;

            /**
             * 同时显示条数上限（安全阀）。
             *
             * <p>默认 <b>0 = 无限制</b>：同批消息（一次逻辑操作内连续发送的多条）经
             * {@code Messenger.notifyAll} 批量 API 入队时不受本条数限制挤除，
             * 仅单条入队仍按本上限挤除最旧。</p>
             */
            @ConfigEntry.Gui.Tooltip
            public int maxToasts = 0;
        }
    }

    @Override
    public void validatePostLoad() throws ConfigData.ValidationException {
        if (gui.toast.maxWidthPercent < 10 || gui.toast.maxWidthPercent > 80) {
            throw new ConfigData.ValidationException(
                    "gui.toast.maxWidthPercent must be within [10, 80]");
        }
        if (gui.toast.durationTicks < 20 || gui.toast.durationTicks > 600) {
            throw new ConfigData.ValidationException(
                    "gui.toast.durationTicks must be within [20, 600]");
        }
        if (gui.toast.maxToasts < 0) {
            throw new ConfigData.ValidationException("gui.toast.maxToasts must be >= 0");
        }
    }
}

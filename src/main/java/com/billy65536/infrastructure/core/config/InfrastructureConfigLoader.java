package com.billy65536.infrastructure.core.config;

import com.billy65536.infrastructure.InfrastructureMod;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * infrastructure 主模块固定配置的加载器：AutoConfig 的薄封装。
 *
 * <p>持久化完全由 AutoConfig 的 {@code GsonConfigSerializer} 接管，
 * 写入 {@code config/infrastructure-config.json}（对应 {@code infrastructure:config} 段）。</p>
 *
 * <p>{@link #register()} 必须在任何 {@link #get()} 调用之前执行（即
 * {@code InfrastructureModule.onInitializeModule()}）。</p>
 */
public final class InfrastructureConfigLoader {

    private static ConfigHolder<InfrastructureConfig> holder;

    private InfrastructureConfigLoader() {}

    /** 注册 AutoConfig。幂等，重复调用直接返回。 */
    public static void register() {
        if (holder != null) return;
        holder = AutoConfig.register(InfrastructureConfig.class, GsonConfigSerializer::new);
        InfrastructureMod.LOGGER.info("AutoConfig registered for InfrastructureConfig.");
    }

    /**
     * 返回 AutoConfig 持有的活动配置实例。
     *
     * <p>不可缓存返回值：AutoConfig 的 ConfigHolder 在 {@code load()} 时会替换
     * 内部实例，缓存引用会读到陈旧对象。每次访问都应重新调用本方法。</p>
     */
    public static InfrastructureConfig get() {
        return holder().getConfig();
    }

    /** 将当前配置持久化到磁盘。 */
    public static void save() {
        holder().save();
    }

    private static ConfigHolder<InfrastructureConfig> holder() {
        if (holder == null) {
            throw new IllegalStateException(
                    "InfrastructureConfigLoader.register() must be called before accessing the config.");
        }
        return holder;
    }
}

package com.billy65536.infrastructure.core.module;

import java.util.List;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.config.InfrastructureConfig;
import com.billy65536.infrastructure.core.config.InfrastructureConfigLoader;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import me.shedaniel.autoconfig.AutoConfig;

/**
 * infrastructure 主模块对 {@link IModule} 的实现，承载框架级固定配置。
 *
 * <p>本类通过 Java SPI 自动发现（见
 * {@code META-INF/services/com.billy65536.infrastructure.core.module.IModule}），
 * 由 {@link ModuleRegistry#discover()} 统一登记，无需在启动代码中显式注册。</p>
 *
 * <p>配置段：{@code infrastructure:config} —— 主模块固定配置（AutoConfig 模型，
 * 嵌套路径 {@code gui.toast.<字段>}），GUI 恢复为 AutoConfig 原生界面，
 * 持久化到 {@code config/infrastructure-config.json}。</p>
 *
 * <p>版本号为模块<b>独立</b>的日期式版本（{@code YYYYMMDD.N}），与宿主模组的
 * {@code mod_version} 解耦，改动本模块时手工递增。</p>
 */
public final class InfrastructureModule implements IModule {

    private static final String ID = "infrastructure";

    /**
     * 模块自身版本，格式 {@code YYYYMMDD.N}（日期 + 当日第几次更新）。
     *
     * <p>与宿主模组的 {@code mod_version} 解耦：改动本模块时手工递增本常量。</p>
     */
    private static final String VERSION = "20260822.1";

    /** 供 Java SPI 实例化；登记由 {@code ModuleRegistry.discover()} 统一触发。 */
    public InfrastructureModule() {}

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public Text getName() {
        return Text.translatable("infrastructure.msg.module_infrastructure_name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.msg.module_infrastructure_desc");
    }

    // =================== 初始化 ===================
    @Override
    public void onInitializeModule() {
        InfrastructureConfigLoader.register();
    }

    // ==================== 配置 ====================

    @Override
    public List<ConfigDescriptor> getConfigDescriptors() {
        // infrastructure:config —— 主配置（AutoConfig 模型），GUI 恢复为 AutoConfig 原生界面。
        ConfigPath configPath = ConfigPath.of(ID, "config", "");
        ConfigDescriptor configDesc = ConfigDescriptor.withGui(
                configPath,
                InfrastructureConfigLoader::get,
                new InfrastructureConfig(),
                InfrastructureModule::openConfigGui);
        return List.of(configDesc);
    }

    /** 打开 infrastructure:config 的 AutoConfig 原生 GUI（parent 为当前界面）。 */
    private static void openConfigGui() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            Screen parent = client.currentScreen;
            client.setScreen(AutoConfig.getConfigScreen(InfrastructureConfig.class, parent).get());
        }
    }

    @Override
    public void saveConfig() {
        InfrastructureConfigLoader.save();
    }

    // ==================== 命令 ====================

    /** 主模块不贡献命令。 */
    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return null;
    }
}

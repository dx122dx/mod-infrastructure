package com.billy65536.infrastructure.debugger;

import java.util.Collection;
import java.util.List;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.debugger.config.DebuggerConfig;
import com.billy65536.infrastructure.debugger.config.DebuggerConfigLoader;
import com.billy65536.infrastructure.debugger.config.DebuggerFeatureConfig;
import com.billy65536.infrastructure.debugger.config.DebuggerFeaturesScreen;
import com.billy65536.infrastructure.debugger.config.FeatureStateStore;
import com.billy65536.infrastructure.debugger.pack.PackManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import me.shedaniel.autoconfig.AutoConfig;

/**
 * debugger 子模块对 {@link IModule} 的实现，使 debugger 以「模块」身份接入 infrastructure 核心框架。
 *
 * <p>本类通过 Java SPI 自动发现（见
 * {@code META-INF/services/com.billy65536.infrastructure.core.module.IModule}），
 * 由 {@link com.billy65536.infrastructure.core.module.ModuleRegistry#discover()} 统一登记，
 * 无需在启动代码中显式注册。登记后：</p>
 * <ul>
 *   <li>{@code /inf info} 能列出 debugger 并显示其贡献的命令与配置路径；</li>
 *   <li>{@code /inf config get|set|reset debugger:<path>} 可统一读写其配置
 *       （{@link DebuggerConfigLoader} 持有的配置对象）；</li>
 *   <li>{@code /inf dbg ...} 命令树在登记时统一挂入
 *       {@link com.billy65536.infrastructure.core.module.ModuleCommandRegistrar}，
 *       不再由 {@code InfrastructureCommands} 显式挂载。</li>
 * </ul>
 *
 * <p>配置分两段存放，各自独立 GUI：</p>
 * <ul>
 *   <li>{@code debugger:config} —— 主配置（AutoConfig 模型），GUI 恢复为 AutoConfig 原生界面，
 *       持久化到 {@code config/debugger-config.json}；</li>
 *   <li>{@code debugger:feature} —— 调试特性开关（动态 Map，数量由运行时注册决定），
 *       持久化到 {@code config/debugger-features.json}，GUI 为独立的特性开关界面。</li>
 * </ul>
 *
 * <p>版本号为模块<b>独立</b>的日期式版本（{@code YYYYMMDD.N}），与宿主模组的
 * {@code mod_version} 解耦，改动本模块时手工递增。</p>
 */
public final class DebuggerModule implements IModule {

    private static final String ID = "debugger";

    /**
     * 模块自身版本，格式 {@code YYYYMMDD.N}（日期 + 当日第几次更新）。
     *
     * <p>与宿主模组的 {@code mod_version} 解耦：改动本模块时手工递增本常量。</p>
     */
    private static final String VERSION = "20260812.1";

    /** 供 Java SPI 实例化；登记由 {@code ModuleRegistry.discover()} 统一触发。 */
    public DebuggerModule() {}

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
        return Text.translatable("infrastructure.msg.module_debugger_name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.msg.module_debugger_desc");
    }

    // =================== 初始化 ===================
    @Override
    public void onInitializeModule() {
        DebuggerConfigLoader.register();
        FeatureStateStore.load();
        PackManager.registerAll();
    }

    // ==================== 配置 ====================

    @Override
    public List<ConfigDescriptor> getConfigDescriptors() {
        // debugger:config —— 主配置（AutoConfig 模型），GUI 恢复为 AutoConfig 原生界面。

        ConfigPath configPath = ConfigPath.of(ID, "config", "");
        ConfigDescriptor configDesc = ConfigDescriptor.withGui(
                configPath,
                DebuggerConfigLoader::get,
                new DebuggerConfig(),
                DebuggerModule::openConfigGui);

        // debugger:feature —— 调试特性开关（动态键配置，非静态字段），独立 GUI。
        // 配置对象实现 IDynamicConfig，键 = 特性 id 字符串；/inf config get|set|reset
        // debugger:feature/<id> 与路径/值补全均经 ConfigAccessor 派发到该接口生效。
        ConfigPath featurePath = ConfigPath.of(ID, "feature", "");
        ConfigDescriptor featureDesc = ConfigDescriptor.withGui(
                featurePath,
                () -> new DebuggerFeatureConfig(),
                new DebuggerFeatureConfig(),
                DebuggerModule::openFeatureGui);

        return List.of(configDesc, featureDesc);
    }

    /** 打开 debugger:config 的 AutoConfig 原生 GUI（parent 为当前界面）。 */
    private static void openConfigGui() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            Screen parent = client.currentScreen;
            client.setScreen(AutoConfig.getConfigScreen(DebuggerConfig.class, parent).get());
        }
    }

    /** 打开 debugger:feature 的独立特性开关 GUI（parent 为当前界面）。 */
    private static void openFeatureGui() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            Screen parent = client.currentScreen;
            client.setScreen(DebuggerFeaturesScreen.create(parent));
        }
    }

    @Override
    public void saveConfig() {
        DebuggerConfigLoader.save();
        FeatureStateStore.save();
    }

    // ==================== 命令 ====================

    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return DebuggerCommands.buildDbgCommands();
    }

    @Override
    public Collection<String> getCommandLiterals() {
        return List.of("dbg");
    }
}

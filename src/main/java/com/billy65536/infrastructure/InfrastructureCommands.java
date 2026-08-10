package com.billy65536.infrastructure;

import java.util.List;
import java.util.Locale;

import com.billy65536.infrastructure.core.config.ConfigAccessor;
import com.billy65536.infrastructure.core.config.ConfigAccessException;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigLockedException;
import com.billy65536.infrastructure.core.config.ConfigManager;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleCommandRegistrar;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.util.cli.ArgParser;
import com.billy65536.infrastructure.util.cli.CliCompletion;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /inf} 根命令的注册与构建。
 *
 * <p>根命令为 {@code /inf}（亦可作为 {@code /infrastructure} 调用，两者等价）。
 * 提供以下子命令：</p>
 * <ul>
 *   <li>{@code config} —— 模块配置统一访问（{@code get|set|reset|reload|gui <module:path>}）</li>
 *   <li>{@code info} —— 显示模组自身信息及全部已注册模块概览 / 指定模块详情</li>
 *   <li>各模块通过 {@link ModuleCommandRegistrar} 登记的命令节点（如 debugger 的 {@code /inf dbg}）</li>
 * </ul>
 *
 * <p>模块命令节点由 {@link ModuleRegistry#register(IModule)} 在模块登记时统一挂入登记器，
 * 本类仅消费登记结果，不自行遍历模块。</p>
 *
 * <p>{@link #register()} 可以在模块发现之前调用：命令树在
 * {@link ClientCommandRegistrationCallback} 触发时（进入世界）才构建，届时模块已由
 * {@code CLIENT_STARTED} 完成发现；回调内仍会调用一次幂等的
 * {@link ModuleRegistry#discover()} 作为兜底。</p>
 */
public final class InfrastructureCommands {

    private InfrastructureCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 兜底：模块发现正常在 CLIENT_STARTED 完成；若该事件因故未触发
            // （集成测试 / 非常规启动流程），此处补一次幂等发现，保证模块命令不缺失
            ModuleRegistry.discover();
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("inf");
            root.then(buildConfigCommand());
            root.then(buildInfoCommand());
            // 挂载各模块登记的命令节点（登记已在 ModuleRegistry.register 时完成）
            for (LiteralArgumentBuilder<FabricClientCommandSource> node : ModuleCommandRegistrar.getAllNodes()) {
                root.then(node);
            }
            dispatcher.register(root);
        });
    }

    // ==================== /inf config ====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildConfigCommand() {
        var config = ClientCommandManager.literal("config");

        // /inf config get <path:greedyString>
        // path 形如 module:id/field.path，段名为默认值 config 时可简写为 module:field.path。
        config.then(ClientCommandManager.literal("get")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .executes(ctx -> configGet(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "path")))));

        // /inf config set <assignments:greedyString>
        // assignments 形如 [module:id/field=value ...]，可多条以空白分隔（批量设置）。
        config.then(ClientCommandManager.literal("set")
                .then(ClientCommandManager.argument("assignments", StringArgumentType.greedyString())
                        .suggests(CONFIG_ASSIGNMENT_SUGGESTIONS)
                        .executes(ctx -> configSet(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "assignments")))));

        // /inf config reset <path:greedyString>
        config.then(ClientCommandManager.literal("reset")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .executes(ctx -> configReset(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "path")))));

        // /inf config reload [module:config_id] —— 重新加载模块配置（含锁定值重放）
        // 目标形如 module:id，段名为默认值 config 时可简写为 module；缺省重载全部模块
        config.then(ClientCommandManager.literal("reload")
                .executes(ctx -> configReload(ctx.getSource().getClient(), null))
                .then(ClientCommandManager.argument("configId", StringArgumentType.greedyString())
                        .suggests(CONFIG_TARGET_SUGGESTIONS)
                        .executes(ctx -> configReload(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "configId")))));

        // /inf config gui [module:config_id] —— 打开该配置段的 GUI（缺省取首个含 GUI 的配置段）
        config.then(ClientCommandManager.literal("gui")
                .executes(ctx -> configGui(ctx.getSource().getClient(), null))
                .then(ClientCommandManager.argument("configId", StringArgumentType.greedyString())
                        .suggests(CONFIG_GUI_TARGET_SUGGESTIONS)
                        .executes(ctx -> configGui(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "configId")))));

        return config;
    }

    private static int configGet(net.minecraft.client.MinecraftClient client, String fullPath) {
        try {
            Object value = ConfigManager.getValue(fullPath);
            Object def = ConfigManager.getDefaultValue(fullPath);
            MutableText out = Text.literal("")
                    .append(Text.literal(fullPath).formatted(Formatting.GOLD))
                    .append(Text.literal(" = ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA))
                    .append(Text.literal("  (type: ").formatted(Formatting.GRAY))
                    .append(Text.literal(ConfigAccessor.getTypeName(
                                    ConfigManager.findDescriptorByPath(fullPath),
                                    ConfigManager.dotPathOf(fullPath)))
                            .formatted(Formatting.GRAY))
                    .append(Text.literal(", default: ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(def)).formatted(Formatting.GRAY))
                    .append(Text.literal(")").formatted(Formatting.GRAY));
            send(client, out);
            return 1;
        } catch (ConfigAccessException e) {
            send(client, Text.translatable("infrastructure.msg.config_error", fullPath, e.getMessage())
                    .formatted(Formatting.RED));
            return 0;
        }
    }

    private static int configSet(net.minecraft.client.MinecraftClient client, String assignments) {
        // 用 core.cli.ArgParser 把整串解析为若干 key[=value] 条目，支持批量设置
        List<ArgParser.Assignment> items = ArgParser.parseAssignments(assignments);
        if (items.isEmpty()) {
            send(client, Text.translatable("infrastructure.msg.config_set_usage").formatted(Formatting.RED));
            return 0;
        }
        int applied = 0;
        for (ArgParser.Assignment a : items) {
            try {
                Object old = ConfigManager.getValue(a.key);
                ConfigManager.setValue(a.key, a.value);
                applied++;
                send(client, Text.translatable("infrastructure.msg.config_set",
                                Text.literal(a.key).formatted(Formatting.GOLD),
                                Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                                Text.literal(a.value).formatted(Formatting.GREEN)));
            } catch (ConfigAccessException e) {
                send(client, Text.translatable("infrastructure.msg.config_error",
                                a.key, e.getMessage()).formatted(Formatting.RED));
            } catch (ConfigLockedException e) {
                send(client, Text.translatable("infrastructure.msg.config_locked",
                                a.key, e.getViolatedPolicy(), e.getOriginExecutor()).formatted(Formatting.RED));
            }
        }
        if (applied > 0) {
            saveModuleOfPath(client, items.get(0).key);
        }
        return applied > 0 ? 1 : 0;
    }

    private static int configReset(net.minecraft.client.MinecraftClient client, String fullPath) {
        try {
            Object old = ConfigManager.getValue(fullPath);
            ConfigManager.resetValue(fullPath);
            saveModuleOfPath(client, fullPath);
            send(client, Text.translatable("infrastructure.msg.config_reset",
                            Text.literal(fullPath).formatted(Formatting.GOLD),
                            Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                            Text.literal(String.valueOf(ConfigManager.getValue(fullPath)))
                                    .formatted(Formatting.GREEN)));
            return 1;
        } catch (ConfigAccessException e) {
            send(client, Text.translatable("infrastructure.msg.config_error",
                            fullPath, e.getMessage()).formatted(Formatting.RED));
            return 0;
        } catch (ConfigLockedException e) {
            send(client, Text.translatable("infrastructure.msg.config_locked",
                            fullPath, e.getViolatedPolicy(), e.getOriginExecutor()).formatted(Formatting.RED));
            return 0;
        }
    }

    /**
     * /inf config reload [module:config_id] —— 重新加载模块配置。
     *
     * <p>缺省 reload 全部已登记模块；给定目标时按 {@code module:id} 定位配置段
     * （段名为默认值 {@code config} 时可省略成 {@code module}），仅重放该段的锁定值。
     * 重载后由 ConfigLocker.applyAll 重放锁定强制值（防绕过）。</p>
     */
    private static int configReload(net.minecraft.client.MinecraftClient client, String target) {
        if (target == null || target.isEmpty()) {
            for (IModule m : ModuleRegistry.getAll()) {
                m.saveConfig();
            }
            ConfigLocker.applyAll(allDescriptors());
            send(client, Text.translatable("infrastructure.msg.config_reloaded_all")
                    .formatted(Formatting.GREEN));
            return 1;
        }
        ConfigDescriptor descriptor = ConfigManager.findDescriptorByTarget(target);
        if (descriptor == null) {
            send(client, Text.translatable("infrastructure.msg.config_target_not_found", target)
                    .formatted(Formatting.RED));
            return 0;
        }
        IModule module = ModuleRegistry.get(descriptor.path().module());
        if (module != null) module.saveConfig();
        ConfigLocker.applyAll(List.of(descriptor));
        send(client, Text.translatable("infrastructure.msg.config_reloaded",
                        descriptor.path().targetString()).formatted(Formatting.GREEN));
        return 1;
    }

    /**
     * /inf config gui [module:config_id] —— 打开指定配置段的 GUI。
     * 缺省打开第一个含 GUI 回调的配置段；给定目标时按 {@code module:id} 精确定位
     * （段名为默认值 {@code config} 时可省略成 {@code module}）。
     */
    private static int configGui(net.minecraft.client.MinecraftClient client, String target) {
        // 无参数：打开复合配置总览屏（统一入口，等同 ModMenu 的「设置」按钮）
        if (target == null || target.isEmpty()) {
            // 必须用 send（延迟到下一帧）而非 execute：命令运行于客户端主线程，execute 会同步切屏，
            // 随后聊天框关闭的 setScreen(null) 会将其覆盖，导致「有提示但屏幕不出现」。
            // 延迟到聊天框关闭后再切屏，与 openGuiOnClient 的 client.send 行为一致。
            client.send(() -> client.setScreen(
                    com.billy65536.infrastructure.core.gui.CompositeConfigScreen.create(
                            client.currentScreen)));
            send(client, Text.translatable("infrastructure.msg.config_composite_opened")
                    .formatted(Formatting.GREEN));
            return 1;
        }
        ConfigDescriptor descriptor;
        {
            descriptor = ConfigManager.findDescriptorByTarget(target);
            if (descriptor == null) {
                send(client, Text.translatable("infrastructure.msg.config_target_not_found", target)
                        .formatted(Formatting.RED));
                return 0;
            }
        }
        if (descriptor.openGuiOnClient()) {
            send(client, Text.translatable("infrastructure.msg.config_gui_opened",
                            descriptor.path().targetString()).formatted(Formatting.GREEN));
            return 1;
        }
        send(client, Text.translatable("infrastructure.msg.config_no_gui",
                        descriptor.path().targetString()).formatted(Formatting.RED));
        return 0;
    }

    /** set/reset 后持久化：按路径定位模块并 saveConfig。 */
    private static void saveModuleOfPath(net.minecraft.client.MinecraftClient client, String fullPath) {
        IModule m = ConfigManager.findModuleOfPath(fullPath);
        if (m != null) m.saveConfig();
    }

    /** 全部已登记模块的全部描述符（供 reload all）。 */
    private static java.util.List<ConfigDescriptor> allDescriptors() {
        java.util.List<ConfigDescriptor> all = new java.util.ArrayList<>();
        for (IModule m : ModuleRegistry.getAll()) {
            all.addAll(m.getConfigDescriptors());
        }
        return all;
    }

    /**
     * 配置路径补全（get / reset / set 的 key 部分）：基于已输入前缀，
     * 用 {@link CliCompletion} 的层级模式向下钻取一层，支持含 {@code .} 的嵌套路径。
     *
     * <p>候选串取自 {@code ConfigPath.toUserString()}，即默认段名 {@code config}
     * <b>已省略</b>的最简形态（{@code module:field.path}），与
     * {@code ConfigPath.parse} 的省略规则严格对称。段名非 {@code config} 的模块
     * 给出完整形态 {@code module:id/field.path}，故分隔符集合需含 {@code /}。</p>
     */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_PATH_SUGGESTIONS =
            CliCompletion.builder()
                    .separators(".:/")
                    .keySource(ctx -> ConfigManager.suggestPathsFull(prefixOf(ctx, "path")))
                    .build();

    /**
     * 配置赋值补全（set 的 {@code <assignments>} 参数）：层级模式 + assignment + multiple。
     * <ul>
     *   <li>逐层钻取配置路径，补全到叶子后追加 {@code =} 候选；</li>
     *   <li>通过 {@code valueProvider} 取得该路径的合法取值（bool/枚举/当前值）一并给出；</li>
     *   <li>多条 {@code path=value} 以空白分隔时自动循环补全。</li>
     * </ul>
     */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_ASSIGNMENT_SUGGESTIONS =
            CliCompletion.builder()
                    .separators(".:/")
                    .assignment(true)
                    .multiple(true)
                    .keySource(ctx -> ConfigManager.suggestPathsFull(prefixOf(ctx, "assignments")))
                    .valueProvider((ctx, key) -> {
                        ConfigDescriptor d = ConfigManager.findDescriptorByPath(key);
                        if (d == null) return List.of();
                        return ConfigAccessor.suggestValues(d, ConfigManager.dotPathOf(key));
                    })
                    .build();

    /**
     * 配置段目标补全（reload 的 {@code <configId>} 参数）：列出全部配置段的
     * {@code module:config_id}（始终含段名，与 {@code /inf info} 的展示格式一致）。
     */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_TARGET_SUGGESTIONS =
            targetSuggestions(false);

    /** 配置段目标补全（gui 的 {@code <configId>} 参数）：仅列出含 GUI 回调的配置段。 */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_GUI_TARGET_SUGGESTIONS =
            targetSuggestions(true);

    /** 构造 {@code module:config_id} 形式的目标补全器。 */
    private static SuggestionProvider<FabricClientCommandSource> targetSuggestions(boolean onlyWithGui) {
        return (ctx, builder) -> {
            // 大小写归一固定 Locale.ROOT，避免 tr_TR 下 'I' 归一异常导致补全静默失效
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String target : ConfigManager.suggestTargets(remaining, onlyWithGui)) {
                builder.suggest(target);
            }
            return builder.buildFuture();
        };
    }

    /** 从命令上下文取某个 greedyString 参数的已输入前缀（用于补全）。 */
    private static String prefixOf(CommandContext<FabricClientCommandSource> ctx, String arg) {
        try {
            return StringArgumentType.getString(ctx, arg);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ==================== /inf info ====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildInfoCommand() {
        return ClientCommandManager.literal("info")
                // /inf info [moduleId] —— 可选模块 id 参数
                .then(ClientCommandManager.argument("moduleId", StringArgumentType.word())
                        .suggests(MODULE_ID_SUGGESTIONS)
                        .executes(ctx -> showModuleInfo(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "moduleId"))))
                // /inf info —— 无参数，显示自身 + 全部模块概览
                .executes(ctx -> showSelfInfo(ctx.getSource().getClient()));
    }

    private static final SuggestionProvider<FabricClientCommandSource> MODULE_ID_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (IModule m : ModuleRegistry.getAll()) {
                    String id = m.getId();
                    if (id.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(id);
                    }
                }
                return builder.buildFuture();
            };

    private static int showSelfInfo(net.minecraft.client.MinecraftClient client) {
        String version = FabricLoader.getInstance()
                .getModContainer(InfrastructureMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        MutableText out = Text.literal("");
        out = out.append(Text.translatable("infrastructure.msg.info_header")
                .formatted(Formatting.GOLD, Formatting.BOLD)).append("\n");
        out = out.append(Text.translatable("infrastructure.msg.info_version", version)
                .formatted(Formatting.GRAY)).append("\n");
        out = out.append(Text.translatable("infrastructure.msg.info_desc")
                .formatted(Formatting.GRAY)).append("\n");
        out = out.append(Text.translatable("infrastructure.msg.info_modules")
                .formatted(Formatting.YELLOW)).append("\n");
        if (ModuleRegistry.size() == 0) {
            out = out.append(Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.list_empty")
                            .formatted(Formatting.GRAY))).append("\n");
        } else {
            for (IModule m : ModuleRegistry.getAll()) {
                out = out.append(Text.literal("  - ")
                                .append(Text.literal(m.getId()).formatted(Formatting.AQUA))
                                .append(Text.literal(" (v" + m.getVersion() + ")").formatted(Formatting.GRAY))
                                .append(Text.literal(": ").formatted(Formatting.GRAY))
                                .append(m.getName().copy().formatted(Formatting.GRAY)))
                        .append("\n");
            }
        }
        send(client, out);
        return 1;
    }

    private static int showModuleInfo(net.minecraft.client.MinecraftClient client, String rawModuleId) {
        IModule module = (rawModuleId == null || rawModuleId.isEmpty())
                ? null : ModuleRegistry.get(rawModuleId);
        if (module == null) {
            send(client, Text.translatable("infrastructure.msg.module_not_found",
                            rawModuleId == null ? "?" : rawModuleId)
                    .formatted(Formatting.RED));
            return 0;
        }
        MutableText out = Text.literal("");
        out = out.append(Text.literal(module.getId())
                .formatted(Formatting.GOLD, Formatting.BOLD)).append("\n");
        // 标签键必须与「整句」键区分：info_version 带 %s 占位符、info_desc 是完整句子，
        // 直接当标签用会渲染出 "版本：%s1.0.0" 与整句拼接的错乱文本
        out = out.append(Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.info_version_label").formatted(Formatting.GRAY))
                .append(Text.literal(module.getVersion()).formatted(Formatting.GRAY))).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.info_name").formatted(Formatting.GRAY))
                .append(module.getName().copy().formatted(Formatting.AQUA))).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.info_desc_label").formatted(Formatting.GRAY))
                .append(module.getDescription().copy().formatted(Formatting.GRAY))).append("\n");

        // 贡献：命令
        out = out.append(Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.info_contrib_commands").formatted(Formatting.YELLOW)))
                .append("\n");
        var literals = module.getCommandLiterals();
        if (literals == null || literals.isEmpty()) {
            out = out.append(Text.literal("    ")
                    .append(Text.translatable("infrastructure.msg.list_empty").formatted(Formatting.GRAY)))
                    .append("\n");
        } else {
            for (String lit : literals) {
                out = out.append(Text.literal("    - /inf " + lit)
                        .formatted(Formatting.AQUA)).append("\n");
            }
        }

        // 贡献：配置路径
        out = out.append(Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.info_contrib_configs").formatted(Formatting.YELLOW)))
                .append("\n");
        // 每个配置段一行：- <module>:<config_id> (N 项)。
        // 逐条列出全部字段路径会在字段多时刷屏；字段级路径改由 /inf config 的补全给出。
        List<ConfigDescriptor> descriptors = module.getConfigDescriptors();
        if (descriptors.isEmpty()) {
            out = out.append(Text.literal("    ")
                    .append(Text.translatable("infrastructure.msg.list_empty").formatted(Formatting.GRAY)))
                    .append("\n");
        } else {
            for (ConfigDescriptor d : descriptors) {
                int count = ConfigAccessor.listPaths(d).size();
                out = out.append(Text.literal("    - ")
                                .append(Text.literal(d.path().targetString()).formatted(Formatting.AQUA))
                                .append(Text.literal(" "))
                                .append(Text.translatable("infrastructure.msg.info_config_count", count)
                                        .formatted(Formatting.GRAY)))
                        .append("\n");
            }
        }
        send(client, out);
        return 1;
    }

    // ==================== 辅助 ====================

    private static void send(net.minecraft.client.MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }
}

package com.billy65536.infrastructure.debugger;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.billy65536.infrastructure.debugger.config.DebuggerConfigLoader;
import com.billy65536.infrastructure.debugger.config.DebuggerFeaturesScreen;

import com.billy65536.infrastructure.debugger.core.action.ActionRegistry;
import com.billy65536.infrastructure.debugger.core.action.IDebugAction;
import com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry;
import com.billy65536.infrastructure.debugger.core.feature.IDebugFeature;
import com.billy65536.infrastructure.util.cli.ArgTokenizer;
import com.billy65536.infrastructure.util.cli.CliCompletion;
import com.billy65536.infrastructure.InfrastructureMod;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * {@code /inf dbg} 命令树的构建与执行逻辑。
 *
 * <p>本模组独立注册自己的根命令，不挂靠任何其他模组的命令树。</p>
 *
 * <p>提供的子命令（均挂在 {@code /inf dbg} 之下）：</p>
 * <ul>
 *   <li>{@code /inf dbg action run <id> [args...]} —— 执行调试动作</li>
 *   <li>{@code /inf dbg action info <id>} —— 查询调试动作的元信息</li>
 *   <li>{@code /inf dbg feat about <id>} —— 查询调试特性的启用状态</li>
 *   <li>{@code /inf dbg feat enable|disable <id>} —— 启用/禁用调试特性</li>
 *   <li>{@code /inf dbg list} —— 列出全部已注册项</li>
 * </ul>
 */
public final class DebuggerCommands {

    private DebuggerCommands() {}

    // ==================== 自动补全 ====================

    /**
     * 构造基于注册表的 id 前缀补全器。
     *
     * @param idSource 提供候选 id 集合的供给器，延迟求值以反映运行时注册变化
     */
    private static SuggestionProvider<FabricClientCommandSource> idSuggestions(
            Supplier<Collection<? extends Identifier>> idSource) {
        return (ctx, builder) -> {
            // 容忍用户已手动输入的前导引号，统一按无引号串做前缀过滤。
            // 大小写归一固定 Locale.ROOT：默认 locale 为 tr_TR 时 'I' 会转成 'ı'，
            // 导致含大写 I 的 id 补全静默失效
            String remaining = builder.getRemaining().replace("\"", "").toLowerCase(Locale.ROOT);
            for (Identifier id : idSource.get()) {
                String idStr = id.toString();
                if (idStr.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(idStr);
                }
            }
            return builder.buildFuture();
        };
    }

    private static final SuggestionProvider<FabricClientCommandSource> ACTION_ID_SUGGESTIONS =
            idSuggestions(() -> ActionRegistry.getAll().stream().map(IDebugAction::getId).toList());

    private static final SuggestionProvider<FabricClientCommandSource> FEATURE_ID_SUGGESTIONS =
            idSuggestions(() -> FeatureRegistry.getAll().stream().map(IDebugFeature::getId).toList());

    /**
     * 扁平候选回退补全器：调用 {@link IDebugAction#suggest} 取得候选，按位置模式处理多参数。
     * 由 {@link CliCompletion} 位置模式负责「只替换正在输入的片段、不覆盖已输入参数、
     * 空白后循环」等逻辑。动作未注册或无候选时退化为无补全。
     */
    private static final SuggestionProvider<FabricClientCommandSource> ACTION_ARGS_FALLBACK =
            CliCompletion.builder()
                    .multiple(true)
                    .positional((ctx, completed) -> {
                        Identifier rawId = ctx.getArgument("id", Identifier.class);
                        IDebugAction action = ActionRegistry.get(normalizeActionId(rawId));
                        if (action == null) return List.of();
                        return action.suggest(ctx.getSource().getClient(), completed);
                    })
                    .build();

    /**
     * 动作参数节点的补全器：先按已输入的 id 找到对应动作。
     * 若动作提供了层级化补全器（{@link IDebugAction#getArgsCompleter()}，基于
     * {@code infrastructure.core.cli.CliCompletion}）则直接委托，实现配置路径按
     * {@code . : /} 分隔逐层钻取；否则回退到 {@link #ACTION_ARGS_FALLBACK}（即
     * {@link IDebugAction#suggest} 的扁平候选列表）。动作未注册时无补全。
     */
    private static final SuggestionProvider<FabricClientCommandSource> ACTION_ARGS_SUGGESTIONS =
            (ctx, builder) -> {
                Identifier rawId = ctx.getArgument("id", Identifier.class);
                IDebugAction action = ActionRegistry.get(normalizeActionId(rawId));
                if (action == null) return builder.buildFuture();
                SuggestionProvider<FabricClientCommandSource> completer = action.getArgsCompleter();
                if (completer != null) return completer.getSuggestions(ctx, builder);
                return ACTION_ARGS_FALLBACK.getSuggestions(ctx, builder);
            };

    // ==================== 命令构建 ====================

    /**
     * 构建 {@code dbg ...} 子树，挂载到 {@code /inf} 根命令之下。
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildDbgCommands() {
        var root = ClientCommandManager.literal("dbg");

        // ===== /inf dbg action run <id> [args...] + action info <id> =====
        var actionNode = ClientCommandManager.literal("action");
        actionNode.then(ClientCommandManager.literal("run")
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(ACTION_ID_SUGGESTIONS)
                        // 带参数形式：args 用 greedyString 整串接收后再做引号感知分词
                        .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .suggests(ACTION_ARGS_SUGGESTIONS)
                                .executes(ctx -> runAction(
                                        ctx.getSource().getClient(),
                                        ctx.getArgument("id", Identifier.class),
                                        StringArgumentType.getString(ctx, "args"))))
                        // 无参数形式
                        .executes(ctx -> runAction(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class),
                                null))));
        actionNode.then(ClientCommandManager.literal("info")
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(ACTION_ID_SUGGESTIONS)
                        .executes(ctx -> showAction(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class)))));
        root.then(actionNode);

        // ===== /inf dbg feat about|enable|disable <id> =====
        var featNode = ClientCommandManager.literal("feat");
        featNode.then(featIdNode("about",
                (client, id) -> showFeature(client, id)));
        featNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openFeatureGui(ctx.getSource().getClient())));
        featNode.then(featIdNode("enable",
                (client, id) -> setFeature(client, id, true)));
        featNode.then(featIdNode("disable",
                (client, id) -> setFeature(client, id, false)));
        root.then(featNode);

        // ===== /inf dbg list =====
        root.then(ClientCommandManager.literal("list")
                .executes(ctx -> listAll(ctx.getSource().getClient())));

        return root;
    }

    /**
     * 构建 {@code <literal> <id>} 形式的特性子命令节点。
     *
     * <p>about / enable / disable 三者结构一致，仅执行逻辑不同，抽出以避免重复。</p>
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> featIdNode(
            String literal, FeatureCommand command) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(FEATURE_ID_SUGGESTIONS)
                        .executes(ctx -> command.execute(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class))));
    }

    /** 特性子命令的执行逻辑，返回值即命令返回码。 */
    @FunctionalInterface
    private interface FeatureCommand {
        int execute(MinecraftClient client, Identifier id);
    }

    // ==================== 命令执行 ====================

    /** 执行指定 id 的调试动作。 */
    private static int runAction(MinecraftClient client, Identifier rawId, String rawArgs) {
        Identifier id = normalizeActionId(rawId);
        IDebugAction action = ActionRegistry.get(id);
        if (action == null) {
            sendMsg(client, Text.translatable("infrastructure.msg.action_not_found", id.toString())
                    .formatted(Formatting.RED));
            return 0;
        }

        String[] args = ArgTokenizer.tokenize(rawArgs);
        if (DebuggerConfigLoader.get().verboseLogging) {
            InfrastructureMod.LOGGER.info("Executing debug action {} with {} arg(s)", id, args.length);
        }

        // 调试代码稳定性天然偏低，异常必须捕获，绝不允许逸出到 Brigadier。
        // 捕获 Throwable 而非 Exception：内置包动作会触碰目标模组的类，
        // 目标模组版本不匹配时抛的是 NoClassDefFoundError / NoSuchMethodError 等 LinkageError
        try {
            action.execute(client, args);
            sendMsg(client, Text.translatable("infrastructure.msg.action_success", id.toString())
                    .formatted(Formatting.GREEN));
            return 1;
        } catch (Throwable t) {
            InfrastructureMod.LOGGER.error("Debug action {} failed", id, t);
            String reason = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
            sendMsg(client, Text.translatable("infrastructure.msg.action_failed", id.toString(), reason)
                    .formatted(Formatting.RED));
            if (DebuggerConfigLoader.get().showActionStackTrace) {
                sendStackTrace(client, t);
            }
            return 0;
        }
    }

    /** 显示指定调试动作的元信息（id / 名称 / 描述）。 */
    private static int showAction(MinecraftClient client, Identifier rawId) {
        Identifier id = normalizeActionId(rawId);
        IDebugAction action = ActionRegistry.get(id);
        if (action == null) {
            sendMsg(client, Text.translatable("infrastructure.msg.action_not_found", id.toString())
                    .formatted(Formatting.RED));
            return 0;
        }
        sendMsg(client, Text.translatable("infrastructure.msg.action_info_id",
                        Text.literal(id.toString()).formatted(Formatting.GOLD))
                .formatted(Formatting.GRAY));
        sendMsg(client, Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.action_info_name")
                        .formatted(Formatting.GRAY))
                .append(action.getName().copy().formatted(Formatting.AQUA)));
        sendMsg(client, Text.literal("  ")
                .append(Text.translatable("infrastructure.msg.action_info_desc")
                        .formatted(Formatting.GRAY))
                .append(action.getDescription().copy().formatted(Formatting.GRAY)));
        return 1;
    }

    /** 显示指定特性的当前启用状态。 */
    private static int showFeature(MinecraftClient client, Identifier rawId) {
        Identifier id = normalizeFeatureId(rawId);
        IDebugFeature feature = FeatureRegistry.get(id);
        if (feature == null) {
            sendMsg(client, Text.translatable("infrastructure.msg.feature_not_found", id.toString())
                    .formatted(Formatting.RED));
            return 0;
        }
        boolean active = FeatureRegistry.isEnabled(id);
        sendMsg(client, Text.translatable("infrastructure.msg.feature_status",
                        Text.literal(id.toString()).formatted(Formatting.GOLD),
                        statusText(active))
                .formatted(Formatting.GRAY));
        sendMsg(client, Text.literal("  ")
                .append(feature.getName().copy().formatted(Formatting.AQUA))
                .append(Text.literal(" - ").formatted(Formatting.GRAY))
                .append(feature.getDescription().copy().formatted(Formatting.GRAY)));
        return 1;
    }

    /** 启用或禁用指定特性。 */
    private static int setFeature(MinecraftClient client, Identifier rawId, boolean value) {
        Identifier id = normalizeFeatureId(rawId);
        if (FeatureRegistry.get(id) == null) {
            sendMsg(client, Text.translatable("infrastructure.msg.feature_not_found", id.toString())
                    .formatted(Formatting.RED));
            return 0;
        }
        boolean changed = FeatureRegistry.setEnabled(id, value);
        if (!changed) {
            sendMsg(client, Text.translatable("infrastructure.msg.feature_unchanged",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(value))
                    .formatted(Formatting.YELLOW));
            return 1;
        }
        String key = value
                ? "infrastructure.msg.feature_enabled"
                : "infrastructure.msg.feature_disabled";
        sendMsg(client, Text.translatable(key, Text.literal(id.toString()).formatted(Formatting.GOLD))
                .formatted(value ? Formatting.GREEN : Formatting.GRAY));
        return 1;
    }

    /** 分节列出全部已注册的动作与特性。 */
    private static int listAll(MinecraftClient client) {
        // Action 分节
        sendMsg(client, Text.translatable("infrastructure.msg.list_actions_title",
                ActionRegistry.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (ActionRegistry.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.list_empty")
                            .formatted(Formatting.GRAY)));
        } else {
            for (IDebugAction a : ActionRegistry.getAll()) {
                sendMsg(client, Text.literal("  ")
                        .append(Text.literal(a.getId().toString()).formatted(Formatting.AQUA))
                        .append(Text.literal(" - ").formatted(Formatting.GRAY))
                        .append(a.getName().copy().formatted(Formatting.GRAY)));
            }
        }

        // Feature 分节
        sendMsg(client, Text.translatable("infrastructure.msg.list_features_title",
                FeatureRegistry.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (FeatureRegistry.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.list_empty")
                            .formatted(Formatting.GRAY)));
        } else {
            for (IDebugFeature f : FeatureRegistry.getAll()) {
                sendMsg(client, Text.literal("  ")
                        .append(statusText(FeatureRegistry.isEnabled(f.getId())))
                        .append(Text.literal(" "))
                        .append(Text.literal(f.getId().toString()).formatted(Formatting.AQUA))
                        .append(Text.literal(" - ").formatted(Formatting.GRAY))
                        .append(f.getName().copy().formatted(Formatting.GRAY)));
            }
        }
        return 1;
    }

    /** 打开特性配置界面（即 debugger:feature 的 GUI）。 */
    private static int openFeatureGui(MinecraftClient client) {
        client.send(() -> { client.setScreen(DebuggerFeaturesScreen.create(client.currentScreen)); });
        return 1;
    }

    // ==================== 辅助 ====================

    private static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    /** 启用状态的彩色文本表示。 */
    private static Text statusText(boolean active) {
        return active
                ? Text.translatable("infrastructure.msg.state_enabled").formatted(Formatting.GREEN)
                : Text.translatable("infrastructure.msg.state_disabled").formatted(Formatting.GRAY);
    }

    /** 发送异常堆栈摘要（最多 5 帧），供排查动作内部错误。 */
    private static void sendStackTrace(MinecraftClient client, Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        int limit = Math.min(trace.length, 5);
        for (int i = 0; i < limit; i++) {
            sendMsg(client, Text.literal("  at " + trace[i].toString()).formatted(Formatting.GRAY));
        }
    }

    /**
     * 归一化 {@link IdentifierArgumentType} 解析出的动作 id。
     *
     * @see #normalizeIdentifier(Identifier, Supplier)
     */
    private static Identifier normalizeActionId(Identifier id) {
        return normalizeIdentifier(id,
                () -> ActionRegistry.getAll().stream().map(IDebugAction::getId).toList());
    }

    /**
     * 归一化 {@link IdentifierArgumentType} 解析出的特性 id。
     *
     * @see #normalizeIdentifier(Identifier, Supplier)
     */
    private static Identifier normalizeFeatureId(Identifier id) {
        return normalizeIdentifier(id,
                () -> FeatureRegistry.getAll().stream().map(IDebugFeature::getId).toList());
    }

    /**
     * 归一化 {@link IdentifierArgumentType} 解析出的 {@link Identifier}。
     *
     * <p>调试动作 / 特性由各上层 mod 以<b>自身</b>命名空间注册（如
     * {@code infdbg:exmaple.class.feat}），因此 <b>显式带命名空间的输入必须
     * 原样保留</b>。</p>
     *
     * <p>仅当输入是裸名时才需要推断命名空间：{@link IdentifierArgumentType} 会把裸名
     * （如 {@code cs.foo}）补成 {@code minecraft} 命名空间。此时在注册表中按 path 回查，
     * 命中唯一项则用其真实命名空间；无命中或有歧义时退回本模组命名空间。</p>
     *
     * @param id       已解析的 id，可为 null
     * @param idSource 注册表内全部 id 的供给器，延迟求值以反映运行时注册变化
     */
    private static Identifier normalizeIdentifier(
            Identifier id, Supplier<Collection<? extends Identifier>> idSource) {
        if (id == null) return InfrastructureMod.id("unknown");
        // 非 minecraft 命名空间 = 用户显式书写，原样尊重
        if (!"minecraft".equals(id.getNamespace())) return id;
        // 裸名：在注册表中按 path 回查真实命名空间
        Identifier matched = null;
        for (Identifier known : idSource.get()) {
            if (known.getPath().equals(id.getPath())) {
                // 多个命名空间下同名，无法判定，退回本模组命名空间由调用方报「未找到」
                if (matched != null) return new Identifier(InfrastructureMod.MOD_ID, id.getPath());
                matched = known;
            }
        }
        return (matched != null) ? matched : new Identifier(InfrastructureMod.MOD_ID, id.getPath());
    }
}

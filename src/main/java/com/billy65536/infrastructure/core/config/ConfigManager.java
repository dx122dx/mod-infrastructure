package com.billy65536.infrastructure.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleRegistry;

/**
 * 模块配置「寻址 / 补全」门面（静态工具）。
 *
 * <p>本层只负责把<b>用户可见的完整路径 → 模块 → 描述符 → 字段路径</b>解析出来，
 * 然后把读写转交给 {@link ConfigAccessor}（真正做反射的层）。它<b>不认识任何反射细节</b>，
 * 也<b>不做</b>安全锁定判定：写入类操作一律下沉到 {@link ConfigAccessor}，
 * 锁定门禁在那里统一收口。</p>
 *
 * <p>命令层（{@code /inf config}）与 {@code ConfigLocker} 共用本层的寻址与补全能力；
 * 而描述符级（已定位字段）的读写由 {@link ConfigAccessor} 直接提供，
 * 使模块配置访问成为 infrastructure 的通用能力。</p>
 *
 * <p>路径解析规则见 {@link ConfigPath}：完整形态 {@code <module>:<id>/<dot.path>}，
 * 省略形态 {@code <module>:<dot.path>}（段名为默认值 {@code config} 时可省）。
 * 本管理器按 module 查模块，再在模块的描述符列表中按 id 匹配段，最后用 dot.path 调
 * {@link ConfigAccessor} 做反射读写。</p>
 */
public final class ConfigManager {

    private ConfigManager() {}

    /**
     * 解析完整路径，返回命中的（模块, 描述符, 字段点分路径）。
     * 任一环缺失即抛 {@link ConfigAccessException}。
     */
    private static Resolved resolve(String fullPath) throws ConfigAccessException {
        ConfigPath cp;
        try {
            cp = ConfigPath.parse(fullPath);
        } catch (IllegalArgumentException e) {
            throw new ConfigAccessException(e.getMessage());
        }
        IModule module = ModuleRegistry.get(cp.module());
        if (module == null) {
            throw new ConfigAccessException("Unknown module: " + cp.module());
        }
        ConfigDescriptor descriptor = findDescriptor(module, cp);
        if (descriptor == null) {
            throw new ConfigAccessException(
                    "Module '" + cp.module() + "' has no config segment '" + cp.id() + "'");
        }
        return new Resolved(module, descriptor, cp.dotPath());
    }

    /**
     * 在模块描述符列表中按段名精确匹配。
     *
     * <p>省略形态的段名已由 {@link ConfigPath#parse(String)} 补为
     * {@link ConfigPath#DEFAULT_ID}，故此处只需精确匹配，无需再做任何回退猜测。</p>
     */
    private static ConfigDescriptor findDescriptor(IModule module, ConfigPath cp) {
        for (ConfigDescriptor d : module.getConfigDescriptors()) {
            ConfigPath dp = d.path();
            if (dp.module().equals(cp.module()) && dp.id().equals(cp.id())) {
                return d;
            }
        }
        return null;
    }

    /**
     * 按描述符级目标串（{@code module:id}，段名为 {@code config} 时可省）取描述符。
     * 模块或段不存在时返回 null。供 {@code /inf config gui|reload} 使用。
     */
    public static ConfigDescriptor findDescriptorByTarget(String target) {
        ConfigPath cp;
        try {
            cp = ConfigPath.parseTarget(target);
        } catch (IllegalArgumentException e) {
            return null;
        }
        IModule module = ModuleRegistry.get(cp.module());
        if (module == null) return null;
        return findDescriptor(module, cp);
    }

    /**
     * 按完整字段路径取其所属描述符；路径非法、模块或段不存在时返回 null。
     * 供命令层展示类型名 / 取值候选（与 {@link #resolve} 共用同一套匹配规则）。
     */
    public static ConfigDescriptor findDescriptorByPath(String fullPath) {
        try {
            return resolve(fullPath).descriptor;
        } catch (ConfigAccessException e) {
            return null;
        }
    }

    /** 取完整路径中的字段点分部分；路径非法时原样返回。 */
    public static String dotPathOf(String fullPath) {
        try {
            return ConfigPath.parse(fullPath).dotPath();
        } catch (IllegalArgumentException e) {
            return fullPath;
        }
    }

    /** 取完整路径所属的模块；路径非法或模块未登记时返回 null。 */
    public static IModule findModuleOfPath(String fullPath) {
        try {
            return ModuleRegistry.get(ConfigPath.parse(fullPath).module());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 解析完整路径并读取字段值（供命令层 get）。 */
    public static Object getValue(String fullPath) throws ConfigAccessException {
        Resolved r = resolve(fullPath);
        return ConfigAccessor.getValue(r.descriptor, r.dotPath);
    }

    /** 解析完整路径并读取默认值（供命令层 get 展示）。 */
    public static Object getDefaultValue(String fullPath) throws ConfigAccessException {
        Resolved r = resolve(fullPath);
        return ConfigAccessor.getDefaultValue(r.descriptor, r.dotPath);
    }

    /**
     * 解析完整路径并写值（供命令层 set）。
     *
     * @throws ConfigLockedException 路径被安全配置锁定
     * @throws ConfigAccessException 路径不存在、格式非法
     */
    public static void setValue(String fullPath, String raw) throws ConfigLockedException, ConfigAccessException {
        Resolved r = resolve(fullPath);
        ConfigAccessor.setValue(r.descriptor, r.dotPath, raw);
    }

    /**
     * 解析完整路径并重置（供命令层 reset）。
     *
     * @throws ConfigLockedException 路径被安全配置锁定
     * @throws ConfigAccessException 路径不存在、格式非法
     */
    public static void resetValue(String fullPath) throws ConfigLockedException, ConfigAccessException {
        Resolved r = resolve(fullPath);
        ConfigAccessor.resetValue(r.descriptor, r.dotPath);
    }

    /** 补全：解析已输入的前缀，返回候选的完整路径串（含用户最简形态）。 */
    public static List<String> suggestPaths(String prefix) {
        List<String> out = new ArrayList<>();
        // 尝试按已输入前缀匹配 module / segment
        String lower = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        for (IModule m : ModuleRegistry.getAll()) {
            for (ConfigDescriptor d : m.getConfigDescriptors()) {
                ConfigPath cp = d.path();
                for (String dotPath : ConfigAccessor.listPaths(d)) {
                    ConfigPath full = ConfigPath.of(cp.module(), cp.id(), dotPath);
                    String user = full.toUserString();
                    if (user.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        out.add(user);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 补全：描述符级目标串候选（{@code module:id}，始终含段名）。
     *
     * @param prefix      已输入前缀（大小写不敏感）
     * @param onlyWithGui true 时仅列出含 GUI 回调的描述符（供 {@code config gui}）
     */
    public static List<String> suggestTargets(String prefix, boolean onlyWithGui) {
        List<String> out = new ArrayList<>();
        String lower = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        for (IModule m : ModuleRegistry.getAll()) {
            for (ConfigDescriptor d : m.getConfigDescriptors()) {
                if (onlyWithGui && d.openGui() == null) continue;
                String target = d.path().targetString();
                if (target.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    out.add(target);
                }
            }
        }
        return out;
    }

    /**
     * 补全：返回候选的<b>显式</b>完整路径串（{@code module:id/path}，段名始终展开，
     * 不做省略）。
     *
     * <p>专门供 {@link com.billy65536.infrastructure.util.cli.CliCompletion} 的层级模式使用：
     * 显式含段名后才能构建出 {@code module → id → path} 三层字典树，使补全在输入命名空间
     * （如 {@code debugger:}）后正确展示配置段（如 {@code config/}、{@code feature/}），
     * 而非直接跳到字段层。与 {@link #suggestPaths(String)} 的省略形态互补。</p>
     */
    public static List<String> suggestPathsFull(String prefix) {
        List<String> out = new ArrayList<>();
        String lower = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        for (IModule m : ModuleRegistry.getAll()) {
            for (ConfigDescriptor d : m.getConfigDescriptors()) {
                ConfigPath cp = d.path();
                for (String dotPath : ConfigAccessor.listPaths(d)) {
                    ConfigPath full = ConfigPath.of(cp.module(), cp.id(), dotPath);
                    String explicit = full.toString();
                    if (explicit.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        out.add(explicit);
                    }
                }
            }
        }
        return out;
    }

    /** 内部解析结果。 */
    private record Resolved(IModule module, ConfigDescriptor descriptor, String dotPath) {}
}

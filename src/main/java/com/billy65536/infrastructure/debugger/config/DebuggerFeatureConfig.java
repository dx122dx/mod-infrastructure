package com.billy65536.infrastructure.debugger.config;

import java.util.Collection;
import java.util.List;

import com.billy65536.infrastructure.core.config.ConfigAccessException;
import com.billy65536.infrastructure.core.config.IDynamicConfig;
import com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry;
import com.billy65536.infrastructure.debugger.core.feature.IDebugFeature;

import net.minecraft.util.Identifier;

/**
 * {@code debugger:feature} 配置段的动态配置对象。
 *
 * <p>调试特性的启用状态由 {@link FeatureStateStore} 以动态 {@code Map<Identifier, Boolean>}
 * 持久化（数量由运行时注册决定，无法用静态字段表达），因此本类<b>不承载任何字段</b>，
 * 而是实现 {@link IDynamicConfig}：以「特性 id 字符串」为动态键，委托
 * {@link FeatureRegistry} 完成读写。通过 {@code /inf config get|set|reset debugger:feature/<id>}
 * 即可对单个调试特性执行与普通字段路径一致的读取（含类型与默认值展示）、写入（布尔解析）、
 * 重置（恢复特性默认）与补全（路径与 {@code true}/{@code false} 值候选）。</p>
 *
 * <p>写入仍受安全锁定门禁与审计约束：{@code ConfigAccessor} 对锁定路径先拦截再进入
 * {@link #set}/{@link #reset}，与反射分支语义一致。GUI（{@link DebuggerFeaturesScreen}）
 * 经 {@link FeatureRegistry#setEnabledDeferred} 批量修改后统一落盘，与命令层互不干扰。</p>
 */
public final class DebuggerFeatureConfig implements IDynamicConfig {

    /** 键 = 特性 id 字符串（{@code namespace:path}，如 {@code infdbg:renderWireframe}）。 */
    @Override
    public Collection<String> listKeys() {
        return FeatureRegistry.getAll().stream()
                .map(f -> f.getId().toString())
                .toList();
    }

    @Override
    public boolean hasKey(String key) {
        return featureOf(key) != null;
    }

    @Override
    public Class<?> getType(String key) {
        return hasKey(key) ? Boolean.class : null;
    }

    @Override
    public Object get(String key) {
        Identifier id = parseId(key);
        return id == null ? null : FeatureRegistry.isEnabled(id);
    }

    @Override
    public Object getDefault(String key) {
        IDebugFeature feature = featureOf(key);
        return feature == null ? null : feature.isDefaultEnabled();
    }

    @Override
    public void set(String key, Object value) throws ConfigAccessException {
        IDebugFeature feature = featureOf(key);
        if (feature == null) {
            throw new ConfigAccessException("Unknown config path: " + key);
        }
        // ConfigAccessor 已按 getType 的 Boolean.class 完成解析，此处值必为 Boolean
        FeatureRegistry.setEnabled(feature.getId(), (Boolean) value);
    }

    @Override
    public void reset(String key) throws ConfigAccessException {
        IDebugFeature feature = featureOf(key);
        if (feature == null) {
            throw new ConfigAccessException("Unknown config path: " + key);
        }
        FeatureRegistry.setEnabled(feature.getId(), feature.isDefaultEnabled());
    }

    @Override
    public List<String> suggestValues(String key) {
        return hasKey(key) ? List.of("true", "false") : List.of();
    }

    /** 解析键为已注册特性；键非法或特性未注册返回 null。 */
    private static IDebugFeature featureOf(String key) {
        Identifier id = parseId(key);
        return id == null ? null : FeatureRegistry.get(id);
    }

    /** 键（{@code namespace:path}）→ Identifier；格式非法返回 null。 */
    private static Identifier parseId(String key) {
        if (key == null) return null;
        return Identifier.tryParse(key);
    }
}

package com.billy65536.infrastructure.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.security.builtin.ConfigLockerPolicyConfig;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IDynamicConfig} 动态配置接入测试。
 *
 * <p>验证两类能力：</p>
 * <ul>
 *   <li><b>ConfigAccessor 动态派发</b>：配置对象实现 {@code IDynamicConfig} 后，
 *       {@code listPaths / hasPath / getValue / getDefaultValue / setValue / resetValue /
 *       getTypeName / suggestPaths / suggestValues} 全部按动态键路由，与反射字段路径行为一致；</li>
 *   <li><b>安全联动</b>：动态路径同样受 {@code ConfigLocker} 锁定门禁约束
 *       （set/reset 被拒、{@code applyLockedValue} 强制值重放），
 *       并可通过 {@link ConfigManager} 以完整路径（模拟 {@code /inf config get|set|reset} 调用链）
 *       读写与补全。</li>
 * </ul>
 *
 * <p>采用内存 fake 实现 {@link IDynamicConfig}（键为 {@code namespace:path} 形态的布尔开关，
 * 对齐 debugger 调试特性的形态），不依赖具体模块实现。</p>
 */
@DisplayName("IDynamicConfig")
class IDynamicConfigTest {

    /** 键为带命名空间的动态键（对齐真实特性 id 形态）。 */
    private static final String KEY_A = "infdbg:renderWireframe";
    private static final String KEY_B = "infdbg:renderOutline";

    private static final String MODULE = "debugger";
    private static final String SEGMENT = "feature";

    /** fake 动态配置的完整路径（debugger:feature/infdbg:renderWireframe）。 */
    private static final String KEY_A_FULL = ConfigPath.of(MODULE, SEGMENT, KEY_A).toString();

    /** 持有当前描述符的测试模块（注册到 ModuleRegistry 以便路径解析）。 */
    private static final TestModule MODULE_INSTANCE = new TestModule();

    /** 内存 fake 动态配置实例（每个用例新建，避免污染）。 */
    private FakeDynamicConfig dynamic;

    private ConfigDescriptor descriptor;

    @BeforeAll
    static void registerModule() {
        ModuleRegistry.register(MODULE_INSTANCE);
    }

    @BeforeEach
    void reset() {
        clearLocks();
        dynamic = new FakeDynamicConfig()
                .register(KEY_A, true)
                .register(KEY_B, false);
        descriptor = ConfigDescriptor.of(
                ConfigPath.of(MODULE, SEGMENT, ""),
                () -> dynamic,
                dynamic);
        MODULE_INSTANCE.setDescriptor(descriptor);
    }

    @AfterEach
    void cleanUp() {
        clearLocks();
    }

    // ==================== 构造辅助 ====================

    /** 把「完整路径 → 强制值」映射交给执行器物化（与 ConfigLockerTest 同法）。 */
    private static void applyLocks(String dotPath, String value) {
        ConfigLockerPolicyConfig.Builder b = ConfigLockerPolicyConfig.builder(ConfigLocker.EXECUTOR_ID);
        b.lock(MODULE, SEGMENT, dotPath, value);
        ConfigLocker.getInstance().onPolicyChanged(b.build());
    }

    private static void clearLocks() {
        ConfigLocker.getInstance().onPolicyChanged(ConfigLockerPolicyConfig.empty());
    }

    // ==================== 枚举 / 存在性 ====================

    @Nested
    @DisplayName("枚举与存在性")
    class Enumerate {

        @Test
        @DisplayName("listPaths 返回全部动态键")
        void listPaths_shouldReturnDynamicKeys() {
            assertTrue(ConfigAccessor.listPaths(descriptor).contains(KEY_A));
            assertTrue(ConfigAccessor.listPaths(descriptor).contains(KEY_B));
            assertEquals(2, ConfigAccessor.listPaths(descriptor).size());
        }

        @Test
        @DisplayName("hasPath 按动态键判定")
        void hasPath_shouldDelegate() {
            assertTrue(ConfigAccessor.hasPath(descriptor, KEY_A));
            assertFalse(ConfigAccessor.hasPath(descriptor, "infdbg:notRegistered"));
        }
    }

    // ==================== 读取（值 / 默认值 / 类型） ====================

    @Nested
    @DisplayName("读取")
    class Read {

        @Test
        @DisplayName("getValue 返回当前值，getDefaultValue 返回默认值")
        void getValueAndDefault_shouldDelegate() throws ConfigAccessException {
            assertEquals(Boolean.TRUE, ConfigAccessor.getValue(descriptor, KEY_A));
            assertEquals(Boolean.FALSE, ConfigAccessor.getValue(descriptor, KEY_B));

            dynamic.set(KEY_A, false);

            assertEquals(Boolean.FALSE, ConfigAccessor.getValue(descriptor, KEY_A));
            assertEquals(Boolean.TRUE, ConfigAccessor.getDefaultValue(descriptor, KEY_A),
                    "默认值来自注册时默认，不随当前值变化");
        }

        @Test
        @DisplayName("未知键 get / getDefault / getTypeName 安全返回 null")
        void unknownKey_reads_shouldBeNull() {
            assertNull(ConfigAccessor.getValue(descriptor, "infdbg:missing"));
            assertNull(ConfigAccessor.getDefaultValue(descriptor, "infdbg:missing"));
            assertNull(ConfigAccessor.getTypeName(descriptor, "infdbg:missing"));
        }

        @Test
        @DisplayName("getTypeName 返回键值类型简名")
        void getTypeName_shouldReturnSimpleName() {
            assertEquals("Boolean", ConfigAccessor.getTypeName(descriptor, KEY_A));
        }
    }

    // ==================== 写入 / 重置 ====================

    @Nested
    @DisplayName("写入与重置")
    class Write {

        @Test
        @DisplayName("setValue 解析布尔串并写入")
        void setValue_shouldParseAndWrite() {
            assertDoesNotThrow(() -> ConfigAccessor.setValue(descriptor, KEY_A, "false"));
            assertEquals(Boolean.FALSE, dynamic.get(KEY_A));

            assertDoesNotThrow(() -> ConfigAccessor.setValue(descriptor, KEY_A, "true"));
            assertEquals(Boolean.TRUE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("setValue 非法布尔串抛 ConfigAccessException 且不改变值")
        void setValue_invalidValue_shouldThrow() {
            assertThrows(ConfigAccessException.class,
                    () -> ConfigAccessor.setValue(descriptor, KEY_A, "not-a-bool"));
            assertEquals(Boolean.TRUE, dynamic.get(KEY_A), "解析失败的写入不得落到配置对象上");
        }

        @Test
        @DisplayName("setValue 未知键抛 Unknown config path")
        void setValue_unknownKey_shouldThrow() {
            ConfigAccessException ex = assertThrows(ConfigAccessException.class,
                    () -> ConfigAccessor.setValue(descriptor, "infdbg:missing", "true"));
            assertTrue(ex.getMessage().contains("Unknown config path"));
        }

        @Test
        @DisplayName("resetValue 恢复特性默认")
        void resetValue_shouldRestoreDefault() throws ConfigAccessException {
            dynamic.set(KEY_A, false);

            assertDoesNotThrow(() -> ConfigAccessor.resetValue(descriptor, KEY_A));

            assertEquals(Boolean.TRUE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("resetValue 未知键抛 Unknown config path")
        void resetValue_unknownKey_shouldThrow() {
            assertThrows(ConfigAccessException.class,
                    () -> ConfigAccessor.resetValue(descriptor, "infdbg:missing"));
        }
    }

    // ==================== 补全 ====================

    @Nested
    @DisplayName("补全")
    class Completion {

        @Test
        @DisplayName("suggestPaths 前缀过滤动态键")
        void suggestPaths_shouldFilterDynamicKeys() {
            List<String> all = ConfigAccessor.suggestPaths(descriptor, "");
            assertEquals(List.of(KEY_A, KEY_B), all);

            List<String> filtered = ConfigAccessor.suggestPaths(descriptor, "infdbg:renderWire");
            assertEquals(List.of(KEY_A), filtered);
        }

        @Test
        @DisplayName("suggestValues 返回布尔候选")
        void suggestValues_shouldReturnBooleanCandidates() {
            assertEquals(List.of("true", "false"), ConfigAccessor.suggestValues(descriptor, KEY_A));
        }

        @Test
        @DisplayName("未知键 suggestValues 返回空列表")
        void suggestValues_unknownKey_shouldBeEmpty() {
            assertEquals(List.of(), ConfigAccessor.suggestValues(descriptor, "infdbg:missing"));
        }
    }

    // ==================== 安全锁定门禁 ====================

    @Nested
    @DisplayName("安全锁定门禁")
    class LockGate {

        @Test
        @DisplayName("被锁路径 setValue 抛 ConfigLockedException 且维持强制值")
        void setValue_lockedPath_shouldBeRejected() throws ConfigAccessException {
            dynamic.set(KEY_A, true);
            applyLocks(KEY_A, "false");

            // 锁定策略物化时即对动态配置重放强制值（onPolicyChanged → applyAllRegistered）
            assertEquals(Boolean.FALSE, dynamic.get(KEY_A), "锁定强制值应立即覆盖当前值");

            assertThrows(ConfigLockedException.class,
                    () -> ConfigAccessor.setValue(descriptor, KEY_A, "true"));
            assertEquals(Boolean.FALSE, dynamic.get(KEY_A), "被拒绝的写入不得覆盖锁定强制值");
        }

        @Test
        @DisplayName("被锁路径 resetValue 同样被拒绝")
        void resetValue_lockedPath_shouldBeRejected() throws ConfigAccessException {
            dynamic.set(KEY_A, false);
            applyLocks(KEY_A, null); // 仅锁定，不强制值

            assertThrows(ConfigLockedException.class,
                    () -> ConfigAccessor.resetValue(descriptor, KEY_A));
            assertEquals(Boolean.FALSE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("applyLockedValue 只能作用于被锁路径")
        void applyLockedValue_unlockedPath_shouldThrow() {
            assertThrows(ConfigAccessException.class,
                    () -> ConfigAccessor.applyLockedValue(descriptor, KEY_A));
        }

        @Test
        @DisplayName("applyLockedValue 写入锁定表声明的强制值")
        void applyLockedValue_shouldWriteForcedValue() throws Exception {
            dynamic.set(KEY_A, true);
            applyLocks(KEY_A, "false");

            Object written = ConfigAccessor.applyLockedValue(descriptor, KEY_A);

            assertEquals(Boolean.FALSE, written);
            assertEquals(Boolean.FALSE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("仅锁定无强制值时不修改配置")
        void applyLockedValue_nullForcedValue_shouldNotModify() throws Exception {
            dynamic.set(KEY_A, true);
            applyLocks(KEY_A, null);

            assertNull(ConfigAccessor.applyLockedValue(descriptor, KEY_A));
            assertEquals(Boolean.TRUE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("ConfigLocker.applyAll 对动态配置重放强制值")
        void applyAll_shouldOverwriteDynamicValue() throws ConfigAccessException {
            dynamic.set(KEY_A, true);
            applyLocks(KEY_A, "false");

            ConfigLocker.applyAll(List.of(descriptor));

            assertEquals(Boolean.FALSE, dynamic.get(KEY_A),
                    "锁定的强制值必须在配置重载后被重放到动态配置对象上");
        }
    }

    // ==================== ConfigManager 完整路径路由（模拟 /inf config 调用链） ====================

    @Nested
    @DisplayName("ConfigManager 完整路径路由")
    class ManagerRouting {

        @Test
        @DisplayName("get 经完整路径读取动态值")
        void getValue_shouldRouteToDynamicConfig() throws Exception {
            assertEquals(Boolean.TRUE, ConfigManager.getValue(KEY_A_FULL));
            assertEquals(Boolean.TRUE, ConfigManager.getDefaultValue(KEY_A_FULL));
        }

        @Test
        @DisplayName("set 经完整路径写入动态值")
        void setValue_shouldRouteToDynamicConfig() throws Exception {
            ConfigManager.setValue(KEY_A_FULL, "false");
            assertEquals(Boolean.FALSE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("reset 经完整路径恢复默认")
        void resetValue_shouldRouteToDynamicConfig() throws Exception {
            dynamic.set(KEY_A, false);
            ConfigManager.resetValue(KEY_A_FULL);
            assertEquals(Boolean.TRUE, dynamic.get(KEY_A));
        }

        @Test
        @DisplayName("未知动态键经完整路径：get 安全返回 null，set 报 Unknown config path")
        void unknownKey_throughManager() throws ConfigAccessException {
            String full = ConfigPath.of(MODULE, SEGMENT, "infdbg:missing").toString();
            assertNull(ConfigManager.getValue(full),
                    "get 对未知动态键安全降级（与 ConfigAccessor.getValue 语义一致）");
            assertThrows(ConfigAccessException.class,
                    () -> ConfigManager.setValue(full, "true"));
            assertThrows(ConfigAccessException.class,
                    () -> ConfigManager.resetValue(full));
        }

        @Test
        @DisplayName("补全经完整路径列出动态键")
        void suggestPathsFull_shouldIncludeDynamicKeys() {
            assertTrue(ConfigManager.suggestPathsFull("").stream()
                    .anyMatch(p -> p.equals(KEY_A_FULL)));
            assertTrue(ConfigManager.suggestPaths("").stream()
                    .anyMatch(p -> p.equals(KEY_A_FULL)));
        }
    }

    /** 内存 fake 动态配置：注册表即「默认值 + 当前值」两个 Map。 */
    static final class FakeDynamicConfig implements IDynamicConfig {
        private final Map<String, Boolean> defaults = new LinkedHashMap<>();
        private final Map<String, Boolean> states = new LinkedHashMap<>();

        FakeDynamicConfig register(String key, boolean defaultValue) {
            defaults.put(key, defaultValue);
            states.put(key, defaultValue);
            return this;
        }

        @Override
        public Collection<String> listKeys() {
            return new ArrayList<>(states.keySet());
        }

        @Override
        public boolean hasKey(String key) {
            return states.containsKey(key);
        }

        @Override
        public Class<?> getType(String key) {
            return hasKey(key) ? Boolean.class : null;
        }

        @Override
        public Object get(String key) {
            return states.get(key);
        }

        @Override
        public Object getDefault(String key) {
            return defaults.get(key);
        }

        @Override
        public void set(String key, Object value) throws ConfigAccessException {
            if (!hasKey(key)) {
                throw new ConfigAccessException("Unknown config path: " + key);
            }
            states.put(key, (Boolean) value);
        }

        @Override
        public void reset(String key) throws ConfigAccessException {
            if (!hasKey(key)) {
                throw new ConfigAccessException("Unknown config path: " + key);
            }
            states.put(key, defaults.get(key));
        }

        @Override
        public List<String> suggestValues(String key) {
            return hasKey(key) ? List.of("true", "false") : List.of();
        }
    }

    /** 持有当前描述符的测试模块，注册到 ModuleRegistry 以便路径解析。 */
    static final class TestModule implements IModule {
        private volatile ConfigDescriptor descriptor;

        void setDescriptor(ConfigDescriptor d) {
            this.descriptor = d;
        }

        @Override
        public String getId() {
            return MODULE;
        }

        @Override
        public String getVersion() {
            return "test";
        }

        @Override
        public Text getName() {
            return Text.literal("test");
        }

        @Override
        public Text getDescription() {
            return Text.literal("test");
        }

        @Override
        public void onInitializeModule() {}

        @Override
        public List<ConfigDescriptor> getConfigDescriptors() {
            return descriptor == null ? List.of() : List.of(descriptor);
        }

        @Override
        public List<String> getCommandLiterals() {
            return List.of();
        }

        @Override
        public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
            return null;
        }
    }
}

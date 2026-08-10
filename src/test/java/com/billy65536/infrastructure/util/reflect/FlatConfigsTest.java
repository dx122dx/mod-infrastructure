package com.billy65536.infrastructure.util.reflect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.billy65536.infrastructure.util.cli.ArgParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlatConfigs 同构反射工具测试。
 *
 * <p>使用包内私有扁平夹具类 {@link SampleConfig}，不依赖 chunkscanner。
 * 覆盖复制、合并、解析容错、展示键顺序、往返一致性与键枚举。</p>
 */
@DisplayName("FlatConfigs 扁平配置反射工具")
class FlatConfigsTest {

    /** 扁平配置夹具：字段名与别名不同，展示键为驼峰。 */
    @SuppressWarnings("unused")
    static final class SampleConfig {
        @FlatConfigs.Key(value = "revisit", display = "revisit")
        public Integer minRevisitIntervalSec;

        @FlatConfigs.Key(value = "inittasks", display = "initTasks")
        public Integer initialTasksPerTick;

        @FlatConfigs.Key(value = "wpname", display = "wpName")
        public String waypointName;

        @FlatConfigs.Key(value = "radius")
        public Double scanRadiusMultiplier;

        SampleConfig() {}
    }

    @Nested
    @DisplayName("copy")
    class CopyTests {
        @Test
        @DisplayName("返回独立实例且字段全等")
        void independentAndEqual() {
            SampleConfig src = new SampleConfig();
            src.minRevisitIntervalSec = 60;
            src.waypointName = "shop";
            SampleConfig cp = FlatConfigs.copy(src);
            assertNotSame(src, cp);
            assertEquals(src.minRevisitIntervalSec, cp.minRevisitIntervalSec);
            assertEquals(src.waypointName, cp.waypointName);
        }

        @Test
        @DisplayName("修改副本不污染源")
        void copyIsolation() {
            SampleConfig src = new SampleConfig();
            src.minRevisitIntervalSec = 60;
            SampleConfig cp = FlatConfigs.copy(src);
            cp.minRevisitIntervalSec = 999;
            assertEquals(60, src.minRevisitIntervalSec);
        }

        @Test
        @DisplayName("null 入参返回 null")
        void nullSource() {
            assertNull(FlatConfigs.copy(null));
        }
    }

    @Nested
    @DisplayName("merge")
    class MergeTests {
        @Test
        @DisplayName("非 null 覆盖、null 字段不覆盖、base 不被污染")
        void mergeSemantics() {
            SampleConfig base = new SampleConfig();
            base.minRevisitIntervalSec = 10;
            base.waypointName = "old";
            SampleConfig delta = new SampleConfig();
            delta.minRevisitIntervalSec = 20;
            // waypointName 为 null，不应覆盖

            SampleConfig r = FlatConfigs.merge(base, delta);
            assertEquals(20, r.minRevisitIntervalSec);
            assertEquals("old", r.waypointName);
            // base 不被污染
            assertEquals(10, base.minRevisitIntervalSec);
        }

        @Test
        @DisplayName("delta 为 null 返回 base 副本")
        void deltaNull() {
            SampleConfig base = new SampleConfig();
            base.minRevisitIntervalSec = 5;
            SampleConfig r = FlatConfigs.merge(base, null);
            assertNotSame(base, r);
            assertEquals(5, r.minRevisitIntervalSec);
        }
    }

    @Nested
    @DisplayName("createFrom")
    class CreateFromTests {
        @Test
        @DisplayName("别名大小写不敏感")
        void aliasCaseInsensitive() {
            SampleConfig c = FlatConfigs.createFrom("REVISIT=30 INITTASKS=7", SampleConfig.class);
            assertNotNull(c);
            assertEquals(30, c.minRevisitIntervalSec);
            assertEquals(7, c.initialTasksPerTick);
        }

        @Test
        @DisplayName("值含 '=' 时按首个 '=' 分割")
        void valueWithEquals() {
            SampleConfig c = FlatConfigs.createFrom("wpname=a=b", SampleConfig.class);
            assertEquals("a=b", c.waypointName);
        }

        @Test
        @DisplayName("未知键 warn 跳过不中断")
        void unknownKeySkipped() {
            SampleConfig c = FlatConfigs.createFrom("bogus=1 revisit=12", SampleConfig.class);
            assertNotNull(c);
            assertEquals(12, c.minRevisitIntervalSec);
        }

        @Test
        @DisplayName("数值格式错跳过该键、其余键仍生效")
        void numberFormatSkipped() {
            SampleConfig c = FlatConfigs.createFrom("revisit=notnum radius=1.5", SampleConfig.class);
            assertNotNull(c);
            assertNull(c.minRevisitIntervalSec);
            assertEquals(1.5, c.scanRadiusMultiplier);
        }

        @Test
        @DisplayName("空输入返回 null")
        void emptyInput() {
            assertNull(FlatConfigs.createFrom((String) null, SampleConfig.class));
            assertNull(FlatConfigs.createFrom("   ", SampleConfig.class));
        }

        @Test
        @DisplayName("全未识别键返回 null")
        void allUnknownReturnsNull() {
            assertNull(FlatConfigs.createFrom("zzz=1 yyy=2", SampleConfig.class));
        }

        @Test
        @DisplayName("无值条目（无 '='）被跳过")
        void noValueSkipped() {
            SampleConfig c = FlatConfigs.createFrom(java.util.List.of(
                    new ArgParser.Assignment("revisit", null, false)
            ), SampleConfig.class);
            assertNull(c);
        }
    }

    @Nested
    @DisplayName("toString / 往返一致性")
    class StringTests {
        @Test
        @DisplayName("使用 display 驼峰键且顺序稳定")
        void displayKeysAndOrder() {
            SampleConfig c = new SampleConfig();
            c.minRevisitIntervalSec = 60;
            c.initialTasksPerTick = 8;
            c.waypointName = "shop";
            assertEquals("revisit=60 initTasks=8 wpName=shop", FlatConfigs.toString(c));
        }

        @Test
        @DisplayName("全 null 返回空串")
        void allNullEmpty() {
            assertEquals("", FlatConfigs.toString(new SampleConfig()));
        }

        @Test
        @DisplayName("createFrom ↔ toString 往返一致")
        void roundTrip() {
            String raw = "revisit=60 inittasks=8 radius=1.5 wpname=shop";
            SampleConfig c = FlatConfigs.createFrom(raw, SampleConfig.class);
            String out = FlatConfigs.toString(c);
            SampleConfig c2 = FlatConfigs.createFrom(out, SampleConfig.class);
            assertEquals(c.minRevisitIntervalSec, c2.minRevisitIntervalSec);
            assertEquals(c.initialTasksPerTick, c2.initialTasksPerTick);
            assertEquals(c.scanRadiusMultiplier, c2.scanRadiusMultiplier);
            assertEquals(c.waypointName, c2.waypointName);
        }
    }

    @Nested
    @DisplayName("isAllNull / keysOf")
    class MiscTests {
        @Test
        @DisplayName("isAllNull：空对象 true、任一非 null 则 false")
        void isAllNullBoundary() {
            SampleConfig c = new SampleConfig();
            assertTrue(FlatConfigs.isAllNull(c));
            c.waypointName = "x";
            assertFalse(FlatConfigs.isAllNull(c));
            assertTrue(FlatConfigs.isAllNull(null));
        }

        @Test
        @DisplayName("keysOf 返回全部别名且与解析器可识别键一致")
        void keysOfComplete() {
            List<String> keys = FlatConfigs.keysOf(SampleConfig.class);
            assertTrue(keys.contains("revisit"));
            assertTrue(keys.contains("inittasks"));
            assertTrue(keys.contains("wpname"));
            assertTrue(keys.contains("radius"));
            // keysOf 只返回声明的别名，不暴露字段名（waypointname 不应出现）
            assertFalse(keys.contains("waypointname"));
            // 解析器能识别所有声明的别名
            for (String k : List.of("revisit", "inittasks", "wpname", "radius")) {
                assertNotNull(FlatConfigs.createFrom(k + "=1", SampleConfig.class));
            }
        }
    }

    /** 自定义序列化夹具：实现 {@link FlatConfigs.Serializable}，serialize 输出 {@code v:<value>}。 */
    @SuppressWarnings("unused")
    static final class Tag implements FlatConfigs.Serializable {
        public String value;

        Tag() {}

        Tag(String value) { this.value = value; }

        @Override
        public String serialize() {
            return "v:" + (value == null ? "" : value);
        }

        public static Tag deserialize(String raw) {
            // 还原到可无损 serialize 的字符串：必须以 "v:" 前缀回写
            if (!raw.startsWith("v:")) {
                throw new IllegalArgumentException("Tag expects 'v:' prefix, got: " + raw);
            }
            return new Tag(raw.substring(2));
        }

        @Override
        public boolean isEmpty() {
            return value == null || value.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Tag t)) return false;
            return value == null ? t.value == null : value.equals(t.value);
        }

        @Override
        public int hashCode() {
            return value == null ? 0 : value.hashCode();
        }
    }

    /** 含 Serializable 字段的扁平配置夹具。 */
    @SuppressWarnings("unused")
    static final class CustomConfig {
        @FlatConfigs.Key("name")
        public String name;

        @FlatConfigs.Key("tag")
        public Tag tag;

        CustomConfig() {}
    }

    @Nested
    @DisplayName("自定义 Serializable 类型")
    class CustomTypeTests {
        @Test
        @DisplayName("createFrom 经 deserialize 还原、toString 经 serialize 输出")
        void parseAndRender() {
            CustomConfig c = FlatConfigs.createFrom("name=bob tag=v:VIP", CustomConfig.class);
            assertNotNull(c);
            assertEquals("bob", c.name);
            assertEquals("VIP", c.tag.value);
            // toString 应渲染 serialize 输出
            assertEquals("name=bob tag=v:VIP", FlatConfigs.toString(c));
        }

        @Test
        @DisplayName("createFrom ↔ toString 往返无损")
        void roundTrip() {
            CustomConfig c = FlatConfigs.createFrom("name=bob tag=v:VIP", CustomConfig.class);
            CustomConfig c2 = FlatConfigs.createFrom(FlatConfigs.toString(c), CustomConfig.class);
            assertEquals(c.name, c2.name);
            assertEquals(c.tag.value, c2.tag.value);
        }

        @Test
        @DisplayName("isAllNull 对 Serializable 字段按 isEmpty 判定")
        void isAllNullWithEmpty() {
            CustomConfig c = new CustomConfig();
            c.name = "x";
            c.tag = new Tag(""); // isEmpty()==true
            // tag 为空，但 name 非空 → 整体非 allNull
            assertFalse(FlatConfigs.isAllNull(c));
            c.name = null;
            c.tag = new Tag(""); // 两个皆空
            assertTrue(FlatConfigs.isAllNull(c));
            c.tag = new Tag("VIP"); // tag 非空
            assertFalse(FlatConfigs.isAllNull(c));
        }

        @Test
        @DisplayName("deserialize 抛异常被容错 warn 跳过该键")
        void deserializeFailureSkipped() {
            CustomConfig c = FlatConfigs.createFrom("name=bob tag=badFormat", CustomConfig.class);
            assertNotNull(c);
            assertEquals("bob", c.name);
            assertNull(c.tag); // 解析失败该键跳过
        }
    }
}

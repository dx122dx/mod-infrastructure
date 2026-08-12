package com.billy65536.infrastructure.core.config;

import java.util.Collection;
import java.util.List;

/**
 * 动态配置访问接口，供「不支持反射访问」的配置对象接入配置框架。
 *
 * <p>普通配置类通过 public 字段承载配置项，{@link ConfigAccessor} 按反射索引读写。
 * 但部分配置对象的键由运行时动态决定（如 debugger 的调试特性，数量由运行时注册产生，
 * 无法用静态字段表达），反射索引为空导致 {@code /inf config} 无法寻址。
 * 实现本接口即向框架声明「以动态键集合为模型」的访问能力，{@link ConfigAccessor}
 * 会在反射路径之外并行派发到本接口方法。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link #set} 接收<b>已解析</b>的值——字符串解析统一由
 *       {@link ConfigAccessor} 按 {@link #getType} 返回的类型完成，接口内不再解析；</li>
 *   <li>{@link #set}/{@link #reset} 对未知键抛 {@link ConfigAccessException}
 *       （"Unknown config path: ..."），与反射分支的错误语义一致；</li>
 *   <li>读写方法（{@link #get}/{@link #getDefault}/{@link #getType}）对未知键
 *       返回 {@code null}（补全与展示需安全降级，不抛异常）。</li>
 * </ul>
 */
public interface IDynamicConfig {

    /** 全部动态键，顺序稳定（影响补全与列举）。 */
    Collection<String> listKeys();

    /** 键是否存在。 */
    boolean hasKey(String key);

    /** 键的值类型（用于 {@code get} 展示与 {@code set} 解析）；未知键返回 {@code null}。 */
    Class<?> getType(String key);

    /** 读当前值；未知键返回 {@code null}。 */
    Object get(String key);

    /** 读默认值；未知键或无默认值返回 {@code null}。 */
    Object getDefault(String key);

    /**
     * 写已解析的值。
     *
     * @throws ConfigAccessException 键不存在或实现内部写入失败
     */
    void set(String key, Object value) throws ConfigAccessException;

    /**
     * 重置为默认值。
     *
     * @throws ConfigAccessException 键不存在或实现内部写入失败
     */
    void reset(String key) throws ConfigAccessException;

    /** value 参数补全候选；未知键返回空列表。 */
    List<String> suggestValues(String key);
}

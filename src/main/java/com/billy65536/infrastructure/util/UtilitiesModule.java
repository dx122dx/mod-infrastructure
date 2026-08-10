package com.billy65536.infrastructure.util;

import com.billy65536.infrastructure.core.module.IModule;

import net.minecraft.text.Text;

public final class UtilitiesModule implements IModule {

    private static final String ID = "utilities";

    /**
     * 模块自身版本，格式 {@code YYYYMMDD.N}（日期 + 当日第几次更新）。
     *
     * <p>与宿主模组的 {@code mod_version} 解耦：改动本模块时手工递增本常量。</p>
     */
    private static final String VERSION = "20260810.1";

    /** 供 Java SPI 实例化；登记由 {@code ModuleRegistry.discover()} 统一触发。 */
    public UtilitiesModule() {}

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
        return Text.translatable("infrastructure.msg.module_utilities_name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.msg.module_utilities_desc");
    }
    
}

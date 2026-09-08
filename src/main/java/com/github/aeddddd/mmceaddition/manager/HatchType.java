package com.github.aeddddd.mmceaddition.manager;

import java.util.Locale;

/**
 * 仓室类型分类（全局仓室管理工具）。
 * <p>
 * 覆盖 MMCE 原版仓室（物品/流体/能源/ME 总线/样板供应器）、机器控制器
 * 以及本模组的全部总成方块。每个类型占用筛选掩码中的一个 bit。
 */
public enum HatchType {
    /** MMCE 物品输入总线。 */
    ITEM_IN,
    /** MMCE 物品输出总线。 */
    ITEM_OUT,
    /** MMCE 流体输入仓。 */
    FLUID_IN,
    /** MMCE 流体输出仓。 */
    FLUID_OUT,
    /** MMCE 能源输入仓。 */
    ENERGY_IN,
    /** MMCE 能源输出仓。 */
    ENERGY_OUT,
    /** MMCE ME 物品输入总线。 */
    ME_ITEM_IN,
    /** MMCE ME 物品输出总线。 */
    ME_ITEM_OUT,
    /** MMCE ME 流体输入仓。 */
    ME_FLUID_IN,
    /** MMCE ME 流体输出仓。 */
    ME_FLUID_OUT,
    /** MMCE ME 样板供应器。 */
    ME_PATTERN_PROVIDER,
    /** MMCE 机器/工厂/并行控制器。 */
    CONTROLLER,
    /** 本模组 ME 异步物品输出总线。 */
    ASYNC_ITEM_OUT,
    /** 本模组 ME 异步流体输出仓。 */
    ASYNC_FLUID_OUT,
    /** 本模组 ME 样板总成。 */
    PATTERN_ASSEMBLY,
    /** 本模组 ME 输出总成仓。 */
    OUTPUT_ASSEMBLY,
    /** 本模组输入总成仓。 */
    INPUT_ASSEMBLY;

    /** 该类型在筛选掩码中的 bit。 */
    public int bit() {
        return 1 << ordinal();
    }

    /** 类型显示名的语言键。 */
    public String langKey() {
        return "hatchadmin.type." + name().toLowerCase(Locale.ROOT);
    }

    /** 包含所有类型的掩码。 */
    public static final int ALL_MASK = (1 << values().length) - 1;
}

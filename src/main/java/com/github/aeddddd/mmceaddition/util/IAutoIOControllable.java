package com.github.aeddddd.mmceaddition.util;

/**
 * 由 Mixin 混入 MMCE 各输入/输出仓 TileEntity 的自动 IO 开关接口。
 * <p>
 * 当前混入目标：{@code TileItemBus}（物品输入/输出总线）、{@code TileEnergyHatch}（能源输入/输出仓）。
 * MMCE CE 2.2.2 的流体仓没有自动 IO 行为，无需开关。
 */
public interface IAutoIOControllable {

    /**
     * 当前是否允许自动 IO（向邻接容器推送/抽取）。
     */
    boolean mmceaddition$isAutoIOEnabled();

    /**
     * 设置是否允许自动 IO。
     */
    void mmceaddition$setAutoIOEnabled(boolean enabled);
}

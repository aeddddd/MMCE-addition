package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;

import javax.annotation.Nullable;

/**
 * 缓冲视图 GUI 的数据来源接口。
 * <p>
 * 由各类无原生 GUI 的仓室 TileEntity 实现，向 {@link ContainerBufferView}
 * 暴露内部的 Long 物品/流体缓冲区（无对应缓冲时返回 null）。
 */
public interface IBufferViewProvider {

    /**
     * @return 物品缓冲区；无物品缓冲时返回 null
     */
    @Nullable
    default LongItemBuffer getViewItemBuffer() {
        return null;
    }

    /**
     * @return 流体缓冲区；无流体缓冲时返回 null
     */
    @Nullable
    default LongFluidBuffer getViewFluidBuffer() {
        return null;
    }

    /**
     * @return GUI 是否显示网络能源信息行
     */
    default boolean hasViewEnergy() {
        return false;
    }

    /**
     * @return 当前网络能源（RF），仅当 {@link #hasViewEnergy()} 为 true 时有意义
     */
    default long getViewEnergy() {
        return 0;
    }

    /**
     * 缓冲区被 GUI 直接修改（绕过常规 handler）后的回调。
     * <p>
     * 实现者应在此重建 slot→变体映射等派生状态（例如输入总成仓的
     * {@code LongInputItemHandler#syncWithBuffer()}）。
     */
    default void onViewBufferModified() {
    }
}

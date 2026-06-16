package com.github.aeddddd.mmceaddition.util;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 把 {@link LongFluidBuffer} 包装为 {@link IFluidHandler}，供 MMCE 配方输出使用。
 * 只接受输入，不允许抽取。
 */
public class LongBufferFluidHandler implements IFluidHandler {

    /**
     * 与原版 MMCE ME 流体输出仓保持一致的可视槽位数。
     */
    private static final int VISIBLE_TANKS = 9;

    private final LongFluidBuffer buffer;

    public LongBufferFluidHandler(LongFluidBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public IFluidTankProperties[] getTankProperties() {
        Map<Fluid, Long> snapshot = buffer.snapshot();
        if (snapshot.isEmpty()) {
            IFluidTankProperties[] props = new IFluidTankProperties[VISIBLE_TANKS];
            for (int i = 0; i < VISIBLE_TANKS; i++) {
                props[i] = new FluidTankProperties(null, Integer.MAX_VALUE);
            }
            return props;
        }
        IFluidTankProperties[] props = new IFluidTankProperties[VISIBLE_TANKS];
        int i = 0;
        for (Map.Entry<Fluid, Long> entry : snapshot.entrySet()) {
            FluidStack content = new FluidStack(entry.getKey(), (int) Math.min(entry.getValue(), Integer.MAX_VALUE));
            props[i++] = new FluidTankProperties(content, Integer.MAX_VALUE);
            if (i >= VISIBLE_TANKS) break;
        }
        while (i < VISIBLE_TANKS) {
            props[i++] = new FluidTankProperties(null, Integer.MAX_VALUE);
        }
        return props;
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        return buffer.fill(resource, doFill);
    }

    @Nullable
    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        return null;
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        return null;
    }
}

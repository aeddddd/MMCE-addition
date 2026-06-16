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
 * <p>
 * 与 {@link LongBufferItemHandler} 类似，只接受输入，不允许抽取。
 */
public class LongBufferFluidHandler implements IFluidHandler {

    /**
     * 暴露给 MMCE 的可视流体槽位数，与原版 MEFluidBus 保持一致。
     */
    private static final int VISIBLE_TANKS = 9;

    private final LongFluidBuffer buffer;

    public LongBufferFluidHandler(LongFluidBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * 返回流体槽属性。
     * <p>
     * 为了通过 MMCE 的配方校验，始终返回 9 个槽位，每个槽位容量为 Integer.MAX_VALUE。
     * 如果缓冲区有流体，按顺序填充前 N 个槽位；其余显示为空。
     */
    @Override
    public IFluidTankProperties[] getTankProperties() {
        Map<Fluid, Long> snapshot = buffer.snapshot();
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
        // 输出仓不允许抽取。
        return null;
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        return null;
    }
}

package com.github.aeddddd.mmceaddition.util;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;

/**
 * 把 {@link LongFluidBuffer} 包装为 {@link IFluidHandler}，供机器配方消耗流体输入。
 */
public class LongInputFluidHandler implements IFluidHandler {

    /**
     * 暴露给 MMCE 的可视流体槽位数。
     */
    private static final int VISIBLE_TANKS = 9;

    private final LongFluidBuffer buffer;

    public LongInputFluidHandler(LongFluidBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * 按缓冲区实际内容逐流体暴露 tank 属性。
     * <p>
     * 注意：MMCE 的 HybridFluidUtils.copyFluidHandlerComponents 会用 MultiFluidTank
     * 快照 getTankProperties() 的内容作为配方检查的副本，如果 contents 为空，
     * 流体输入检查永远失败，因此这里必须报告真实的流体与数量。
     */
    @Override
    public IFluidTankProperties[] getTankProperties() {
        java.util.Map<net.minecraftforge.fluids.Fluid, Long> snapshot = buffer.snapshot();
        IFluidTankProperties[] props = new IFluidTankProperties[VISIBLE_TANKS];
        int i = 0;
        for (java.util.Map.Entry<net.minecraftforge.fluids.Fluid, Long> entry : snapshot.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            FluidStack content = new FluidStack(entry.getKey(), (int) Math.min(entry.getValue(), Integer.MAX_VALUE));
            props[i++] = new FluidTankProperties(content, Integer.MAX_VALUE, true, true);
            if (i >= VISIBLE_TANKS) {
                break;
            }
        }
        while (i < VISIBLE_TANKS) {
            props[i++] = new FluidTankProperties(null, Integer.MAX_VALUE, true, true);
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
        if (resource == null || resource.amount <= 0) {
            return null;
        }
        long current = buffer.getAmount(resource.getFluid());
        long toDrain = Math.min(resource.amount, current);
        if (toDrain <= 0) {
            return null;
        }
        if (doDrain) {
            buffer.extract(resource.getFluid(), toDrain);
        }
        FluidStack drained = resource.copy();
        drained.amount = (int) toDrain;
        return drained;
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        // 无法确定流体类型，返回 null。
        return null;
    }

    /**
     * 从缓冲区抽取指定数量的指定流体。
     */
    public long extractFluid(net.minecraftforge.fluids.Fluid fluid, long amount) {
        return buffer.extract(fluid, amount);
    }

    /**
     * 获取指定流体的当前数量。
     */
    public long getAmount(net.minecraftforge.fluids.Fluid fluid) {
        return buffer.getAmount(fluid);
    }
}

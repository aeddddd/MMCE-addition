package com.github.aeddddd.mmceaddition.util;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 把 {@link LongFluidBuffer} 包装为 {@link IFluidHandler}，供机器配方消耗流体输入。
 * <p>
 * 本类不持有自己的锁：所有读写都委托给 {@link LongFluidBuffer} 的 synchronized 方法
 * （含只读判定 {@link #wouldAcceptAll}），因此判定与写入遵循与 {@code fill} 相同的同步约定。
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

    /**
     * 只读判定：给定的一组流体是否能被本 handler 完整接收（不写入任何内容、不触发观察者）。
     * <p>
     * 判定逻辑与锁都在 {@link LongFluidBuffer#wouldAcceptAll(FluidStack[])} 上，
     * 因此与 {@link #fill} 看到的是同一份状态。
     *
     * @param resources 待填充的流体堆（可含 null 与空堆）
     * @return true 表示全部可完整接收
     */
    public boolean wouldAcceptAll(@Nonnull FluidStack[] resources) {
        return buffer.wouldAcceptAll(resources);
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

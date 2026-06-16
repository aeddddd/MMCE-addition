package com.github.aeddddd.mmceaddition.util;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * 支持 Long 数量上限的流体缓冲区。
 * <p>
 * 与 {@link LongItemBuffer} 对应，用 Map&lt;Fluid, Long&gt; 按流体类型聚合存储。
 * 原版 Forge 的 IFluidTank 通常用 int 表示毫桶（mB），这里用 Long 突破上限。
 */
public class LongFluidBuffer {

    private final Map<Fluid, Long> storage = new HashMap<>();
    private final IBufferObserver observer;

    public LongFluidBuffer() {
        this(null);
    }

    public LongFluidBuffer(IBufferObserver observer) {
        this.observer = observer;
    }

    /**
     * 向缓冲区填充流体，返回实际填充的 mB 数量。
     *
     * @param resource 要填充的流体堆
     * @param doFill   true 为真正填充，false 为模拟
     * @return 实际填充的 mB 数
     */
    public synchronized int fill(@Nonnull FluidStack resource, boolean doFill) {
        if (resource == null || resource.amount <= 0) {
            return 0;
        }
        boolean wasEmpty = storage.isEmpty();
        Fluid fluid = resource.getFluid();
        long current = storage.getOrDefault(fluid, 0L);
        long amount = resource.amount;
        long next = current + amount;
        long accepted = amount;

        if (next < 0) {
            // Long 溢出，只接受剩余空间。
            accepted = Long.MAX_VALUE - current;
            next = Long.MAX_VALUE;
        }

        if (doFill) {
            storage.put(fluid, next);
            if (wasEmpty && !storage.isEmpty() && observer != null) {
                observer.onBufferNonEmpty();
            }
        }

        return (int) accepted;
    }

    /**
     * 从缓冲区抽取流体，返回实际抽取量。
     */
    public synchronized long extract(@Nonnull Fluid fluid, long amount) {
        if (amount <= 0) {
            return 0;
        }
        Long current = storage.get(fluid);
        if (current == null || current <= 0) {
            return 0;
        }
        long toExtract = Math.min(amount, current);
        long next = current - toExtract;
        if (next <= 0) {
            storage.remove(fluid);
        } else {
            storage.put(fluid, next);
        }
        return toExtract;
    }

    public synchronized long getAmount(@Nonnull Fluid fluid) {
        return storage.getOrDefault(fluid, 0L);
    }

    public synchronized long getTotalAmount() {
        long total = 0;
        for (long amount : storage.values()) {
            total += amount;
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    @Nonnull
    public synchronized Map<Fluid, Long> snapshot() {
        return new HashMap<>(storage);
    }

    public synchronized boolean isEmpty() {
        return storage.isEmpty();
    }

    public synchronized void clear() {
        storage.clear();
    }

    public void writeToNBT(@Nonnull NBTTagCompound compound) {
        NBTTagList list = new NBTTagList();
        Map<Fluid, Long> snap = snapshot();
        for (Map.Entry<Fluid, Long> entry : snap.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("FluidName", entry.getKey().getName());
            tag.setLong("Amount", entry.getValue());
            list.appendTag(tag);
        }
        compound.setTag("Fluids", list);
    }

    public void readFromNBT(@Nonnull NBTTagCompound compound) {
        storage.clear();
        if (!compound.hasKey("Fluids")) {
            return;
        }
        NBTTagList list = compound.getTagList("Fluids", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            Fluid fluid = FluidRegistry.getFluid(tag.getString("FluidName"));
            if (fluid == null) {
                continue;
            }
            long amount = tag.getLong("Amount");
            if (amount <= 0) {
                continue;
            }
            storage.put(fluid, amount);
        }
    }
}

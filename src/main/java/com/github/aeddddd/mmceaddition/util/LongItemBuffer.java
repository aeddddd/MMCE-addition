package com.github.aeddddd.mmceaddition.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * 支持 Long 数量上限的物品缓冲区。
 * <p>
 * 原版 Minecraft 和 Forge 的物品堆栈上限是 int（实际上受 maxStackSize 限制，通常是 64）。
 * 这个缓冲区用 Map&lt;ItemVariant, Long&gt; 存储，单种物品数量可以达到 Long.MAX_VALUE，
 * 从而支持高频产出累积，再批量注入 ME 网络。
 */
public class LongItemBuffer {

    /**
     * 实际存储：键是物品变体，值是累计数量。
     */
    private final Map<ItemVariant, Long> storage = new HashMap<>();

    /**
     * 可选的观察者。缓冲区从空变非空时会通知它。
     */
    private final IBufferObserver observer;

    public LongItemBuffer() {
        this(null);
    }

    public LongItemBuffer(IBufferObserver observer) {
        this.observer = observer;
    }

    /**
     * 向缓冲区插入物品，返回未能插入的部分（正常情况为空）。
     * <p>
     * 插入时会按 ItemVariant 合并同类物品。如果总数量超过 Long.MAX_VALUE，
     * 则只接受剩余空间，并返回余量。
     *
     * @param stack 要插入的物品堆
     * @return 剩余物品堆
     */
    @Nonnull
    public synchronized ItemStack insert(@Nonnull ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 记录插入前是否为空，用于触发观察者回调。
        boolean wasEmpty = storage.isEmpty();

        ItemVariant variant = new ItemVariant(stack);
        long count = stack.getCount();
        long current = storage.getOrDefault(variant, 0L);
        long next = current + count;

        if (next < 0) {
            // Long 溢出，说明已经到达上限。只接受剩余空间。
            long accepted = Long.MAX_VALUE - current;
            storage.put(variant, Long.MAX_VALUE);
            ItemStack remainder = stack.copy();
            remainder.setCount((int) (count - accepted));
            notifyIfNonEmpty(wasEmpty);
            return remainder;
        }

        storage.put(variant, next);
        notifyIfNonEmpty(wasEmpty);
        return ItemStack.EMPTY;
    }

    /**
     * 如果插入导致缓冲区从空变非空，通知观察者。
     */
    private void notifyIfNonEmpty(boolean wasEmpty) {
        if (wasEmpty && !storage.isEmpty() && observer != null) {
            observer.onBufferNonEmpty();
        }
    }

    /**
     * 从缓冲区取走指定数量的某种物品，返回实际取走的数量。
     *
     * @param variant 物品变体
     * @param amount  想取走的数量
     * @return 实际取走数量
     */
    public synchronized long extract(@Nonnull ItemVariant variant, long amount) {
        if (amount <= 0) {
            return 0;
        }
        Long current = storage.get(variant);
        if (current == null || current <= 0) {
            return 0;
        }
        long toExtract = Math.min(amount, current);
        long next = current - toExtract;
        if (next <= 0) {
            storage.remove(variant);
        } else {
            storage.put(variant, next);
        }
        return toExtract;
    }

    /**
     * 获取指定变体的当前数量。
     */
    public synchronized long getAmount(@Nonnull ItemVariant variant) {
        return storage.getOrDefault(variant, 0L);
    }

    /**
     * 获取缓冲区内所有物品的总数量。
     */
    public synchronized long getTotalAmount() {
        long total = 0;
        for (long amount : storage.values()) {
            total += amount;
            // 防御性处理：如果总和意外溢出，返回 Long 最大值。
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /**
     * 获取当前缓冲区的只读快照。
     * <p>
     * 用于管理器遍历注入时避免在遍历过程中被修改。
     */
    @Nonnull
    public synchronized Map<ItemVariant, Long> snapshot() {
        return new HashMap<>(storage);
    }

    public synchronized boolean isEmpty() {
        return storage.isEmpty();
    }

    public synchronized void clear() {
        storage.clear();
    }

    /**
     * 序列化到 NBT。
     * <p>
     * 每种变体存为一个 compound：ItemStack 的 NBT + 一个 Long 类型的 Count。
     */
    public void writeToNBT(@Nonnull NBTTagCompound compound) {
        NBTTagList list = new NBTTagList();
        Map<ItemVariant, Long> snap = snapshot();
        for (Map.Entry<ItemVariant, Long> entry : snap.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            ItemStack stack = entry.getKey().toSingleStack();
            stack.writeToNBT(tag);
            tag.setLong("Count", entry.getValue());
            list.appendTag(tag);
        }
        compound.setTag("Items", list);
    }

    /**
     * 从 NBT 反序列化。
     */
    public void readFromNBT(@Nonnull NBTTagCompound compound) {
        storage.clear();
        if (!compound.hasKey("Items")) {
            return;
        }
        NBTTagList list = compound.getTagList("Items", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            long count = tag.getLong("Count");
            if (count <= 0) {
                continue;
            }
            // 先把 Count 改回 1，让 new ItemStack(tag) 能正常反序列化物品类型。
            tag.setInteger("Count", 1);
            ItemStack stack = new ItemStack(tag);
            if (stack.isEmpty()) {
                continue;
            }
            storage.put(new ItemVariant(stack), count);
        }
    }
}

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
 */
public class LongItemBuffer {

    private final Map<ItemVariant, Long> storage = new HashMap<>();

    /**
     * 向缓冲区插入物品，返回未能插入的部分（正常情况为空）。
     *
     * @param stack 要插入的物品堆
     * @return 剩余物品堆
     */
    @Nonnull
    public synchronized ItemStack insert(@Nonnull ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemVariant variant = new ItemVariant(stack);
        long count = stack.getCount();
        long current = storage.getOrDefault(variant, 0L);
        long next = current + count;
        if (next < 0) {
            // 到达 Long 上限，保留剩余部分
            long accepted = Long.MAX_VALUE - current;
            storage.put(variant, Long.MAX_VALUE);
            ItemStack remainder = stack.copy();
            remainder.setCount((int) (count - accepted));
            return remainder;
        }
        storage.put(variant, next);
        return ItemStack.EMPTY;
    }

    /**
     * 从缓冲区取走指定数量的某种物品，返回实际取走的数量。
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
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /**
     * 获取当前缓冲区的只读快照。
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
            // 先把 Count 改回 1 以正常反序列化 ItemStack
            tag.setInteger("Count", 1);
            ItemStack stack = new ItemStack(tag);
            if (stack.isEmpty()) {
                continue;
            }
            storage.put(new ItemVariant(stack), count);
        }
    }
}

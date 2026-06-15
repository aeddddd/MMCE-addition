package com.github.aeddddd.mmceaddition.util;

import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;

/**
 * 把 {@link LongItemBuffer} 包装为 {@link IItemHandlerModifiable}，供 MMCE 配方输出使用。
 * 只接受输入，不允许抽取。
 */
public class LongBufferItemHandler implements IItemHandlerModifiable {

    private final LongItemBuffer buffer;

    public LongBufferItemHandler(LongItemBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        return ItemStack.EMPTY;
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        long current = buffer.getAmount(new ItemVariant(stack));
        long space = Long.MAX_VALUE - current;
        if (space <= 0) {
            return stack.copy();
        }
        if (simulate) {
            if (space >= stack.getCount()) {
                return ItemStack.EMPTY;
            }
            ItemStack remainder = stack.copy();
            remainder.setCount((int) (stack.getCount() - space));
            return remainder;
        }
        return buffer.insert(stack);
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        buffer.insert(stack);
    }
}

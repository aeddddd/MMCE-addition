package com.github.aeddddd.mmceaddition.util;

import hellfirepvp.modularmachinery.common.util.IItemHandlerImpl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;

/**
 * 把 {@link LongItemBuffer} 包装为 {@link IItemHandlerModifiable}，供 MMCE 配方输出使用。
 * <p>
 * 继承 {@link IItemHandlerImpl} 是为了让 MMCE 的 copyComponents 走 .copy() 分支，
 * 避免其 IItemHandlerModifiable 拷贝构造器对多槽位处理不正确的问题。
 * 只接受输入，不允许抽取。
 */
public class LongBufferItemHandler extends IItemHandlerImpl {

    /**
     * 暴露给 MMCE 的输出槽位数，用于通过多物品配方校验。
     */
    private static final int VISIBLE_SLOTS = 200;

    private final LongItemBuffer buffer;

    public LongBufferItemHandler(LongItemBuffer buffer) {
        this.buffer = buffer;
        // 初始化内部数组，使基类状态与 VISIBLE_SLOTS 一致
        this.allowAnySlots = false;
        this.accessibleSides = new EnumFacing[0];
        this.slotLimits = new int[VISIBLE_SLOTS];
        this.inventory = new SlotStackHolder[VISIBLE_SLOTS];
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            this.slotLimits[i] = Integer.MAX_VALUE;
            this.inventory[i] = new SlotStackHolder(i);
            this.inventory[i].itemStack.set(ItemStack.EMPTY);
        }
        this.inSlots = new int[0];
        this.outSlots = new int[VISIBLE_SLOTS];
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            this.outSlots[i] = i;
        }
        this.miscSlots = new int[0];
    }

    @Override
    public int getSlots() {
        return VISIBLE_SLOTS;
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
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (!stack.isEmpty()) {
            buffer.insert(stack);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    @Override
    public IItemHandlerImpl copy() {
        LongItemBuffer copiedBuffer = new LongItemBuffer();
        copiedBuffer.readFromNBT(bufferToNbt());
        return new LongBufferItemHandler(copiedBuffer);
    }

    @Override
    public IItemHandlerImpl fastCopy() {
        return copy();
    }

    @Override
    public IItemHandlerModifiable asGUIAccess() {
        return this;
    }

    private net.minecraft.nbt.NBTTagCompound bufferToNbt() {
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        buffer.writeToNBT(tag);
        return tag;
    }
}

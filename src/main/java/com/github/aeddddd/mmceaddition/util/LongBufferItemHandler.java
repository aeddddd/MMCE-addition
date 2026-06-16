package com.github.aeddddd.mmceaddition.util;

import hellfirepvp.modularmachinery.common.util.IItemHandlerImpl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;

import javax.annotation.Nonnull;

/**
 * 把 {@link LongItemBuffer} 包装为 {@link IItemHandlerModifiable}，供 MMCE 配方输出使用。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>只接受输入，不允许抽取（输出仓不能反向取物品）。</li>
 *   <li>所有槽位都共享同一个 LongItemBuffer，内部按物品变体聚合。</li>
 *   <li>暴露 200 个槽位，以通过 MMCE 多物品/大数量输出配方的空间校验。</li>
 *   <li>继承 MMCE 的 {@link IItemHandlerImpl} 而不是普通实现 IItemHandlerModifiable，
 *       这样 MMCE 的 copyComponents 会调用 .copy()，避免其 IItemHandlerModifiable
 *       拷贝构造器对多槽位处理不正确的问题。</li>
 * </ul>
 */
public class LongBufferItemHandler extends IItemHandlerImpl {

    /**
     * 暴露给 MMCE 的输出槽位数。
     * <p>
     * 原版 MEItemOutputBus 是 36 槽，这里设为 200 以兼容更多输出场景。
     * 注意：这只是“可见槽位”，实际存储不受此限制。
     */
    private static final int VISIBLE_SLOTS = 200;

    /**
     * 被包装的长缓冲区。
     */
    private final LongItemBuffer buffer;

    public LongBufferItemHandler(LongItemBuffer buffer) {
        this.buffer = buffer;

        // 初始化 IItemHandlerImpl 的内部数组，使其与 VISIBLE_SLOTS 一致。
        // 如果不做这一步，基类默认只有 1 个槽，MMCE 拷贝时可能出错。
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
        // 缓冲区不按槽位存储物品，因此对外显示所有槽位都是空的。
        // 真正容量由 insertItem 和 LongItemBuffer 决定。
        return ItemStack.EMPTY;
    }

    /**
     * 尝试把物品插入缓冲区。
     * <p>
     * slot 参数被忽略，因为缓冲区不区分槽位。
     *
     * @param slot     槽位索引（忽略）
     * @param stack    要插入的物品堆
     * @param simulate true 为模拟，false 为真正插入
     * @return 剩余未插入的物品堆
     */
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
        // 输出仓不允许抽取。
        return ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        // setStackInSlot 语义是“设置槽位内容”，但缓冲区没有固定槽位。
        // 这里把非空堆视为一次插入操作。
        if (!stack.isEmpty()) {
            buffer.insert(stack);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    /**
     * 创建 handler 的副本，用于 MMCE 的配方校验。
     * <p>
     * 校验时不应修改真实缓冲区，因此复制一个带独立缓冲区的 handler。
     */
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

    /**
     * 把缓冲区内容序列化为 NBT，用于 copy()。
     */
    private net.minecraft.nbt.NBTTagCompound bufferToNbt() {
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        buffer.writeToNBT(tag);
        return tag;
    }
}

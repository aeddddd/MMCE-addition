package com.github.aeddddd.mmceaddition.util;

import hellfirepvp.modularmachinery.common.util.IItemHandlerImpl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * 把 {@link LongItemBuffer} 包装为 {@link hellfirepvp.modularmachinery.common.util.IItemHandlerImpl}，
 * 供机器配方消耗输入。
 * <p>
 * 与 {@link LongBufferItemHandler}（输出仓）不同，这个 handler 允许 insert 和 extract，
 * 并且会把缓冲区中的物品变体映射到 200 个可见槽位上，使 MMCE 的配方检查/消耗能够正常工作。
 */
public class LongInputItemHandler extends IItemHandlerImpl {

    private static final int VISIBLE_SLOTS = 200;

    private final LongItemBuffer buffer;

    /**
     * 槽位到物品变体的映射。同一变体只能出现在一个槽位中。
     */
    private final ItemVariant[] slotVariants = new ItemVariant[VISIBLE_SLOTS];

    public LongInputItemHandler(LongItemBuffer buffer) {
        this.buffer = buffer;

        this.allowAnySlots = false;
        this.accessibleSides = new EnumFacing[0];
        this.slotLimits = new int[VISIBLE_SLOTS];
        this.inventory = new SlotStackHolder[VISIBLE_SLOTS];
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            this.slotLimits[i] = Integer.MAX_VALUE;
            this.inventory[i] = new SlotStackHolder(i);
            this.inventory[i].itemStack.set(ItemStack.EMPTY);
            this.slotVariants[i] = null;
        }
        this.inSlots = new int[VISIBLE_SLOTS];
        this.outSlots = new int[VISIBLE_SLOTS];
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            this.inSlots[i] = i;
            this.outSlots[i] = i;
        }
        this.miscSlots = new int[0];
    }

    private LongInputItemHandler(LongItemBuffer buffer, ItemVariant[] slotVariants) {
        this(buffer);
        System.arraycopy(slotVariants, 0, this.slotVariants, 0, Math.min(slotVariants.length, VISIBLE_SLOTS));
    }

    @Override
    public int getSlots() {
        return VISIBLE_SLOTS;
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= VISIBLE_SLOTS) {
            return ItemStack.EMPTY;
        }
        ItemVariant variant = slotVariants[slot];
        if (variant == null) {
            return ItemStack.EMPTY;
        }
        long amount = buffer.getAmount(variant);
        if (amount <= 0) {
            slotVariants[slot] = null;
            return ItemStack.EMPTY;
        }
        ItemStack stack = variant.toSingleStack();
        stack.setCount((int) Math.min(amount, Integer.MAX_VALUE));
        return stack;
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot < 0 || slot >= VISIBLE_SLOTS) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        }

        ItemVariant variant = new ItemVariant(stack);

        // 找到该变体已有的槽位，优先合并。
        int targetSlot = findSlotWithVariant(variant);
        if (targetSlot < 0) {
            // 否则使用请求的槽位，如果它为空。
            if (slotVariants[slot] == null) {
                targetSlot = slot;
            } else {
                // 查找任意空槽。
                targetSlot = findEmptySlot();
            }
        }
        if (targetSlot < 0) {
            return stack.copy();
        }

        ItemVariant existingVariant = slotVariants[targetSlot];
        long current = existingVariant == null ? 0 : buffer.getAmount(existingVariant);
        long space = Long.MAX_VALUE - current;
        int count = stack.getCount();
        if (space <= 0) {
            return stack.copy();
        }

        if (simulate) {
            if (space >= count) {
                return ItemStack.EMPTY;
            }
            ItemStack remainder = stack.copy();
            remainder.setCount((int) (count - space));
            return remainder;
        }

        ItemStack remainder = buffer.insert(stack);
        if (slotVariants[targetSlot] == null || !slotVariants[targetSlot].equals(variant)) {
            slotVariants[targetSlot] = variant;
        }
        return remainder;
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot < 0 || slot >= VISIBLE_SLOTS) {
            return ItemStack.EMPTY;
        }
        ItemVariant variant = slotVariants[slot];
        if (variant == null) {
            return ItemStack.EMPTY;
        }
        long available = buffer.getAmount(variant);
        if (available <= 0) {
            slotVariants[slot] = null;
            return ItemStack.EMPTY;
        }
        int toExtract = (int) Math.min(amount, Math.min(available, Integer.MAX_VALUE));
        if (!simulate) {
            buffer.extract(variant, toExtract);
            if (buffer.getAmount(variant) <= 0) {
                slotVariants[slot] = null;
            }
        }
        ItemStack result = variant.toSingleStack();
        result.setCount(toExtract);
        return result;
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (slot < 0 || slot >= VISIBLE_SLOTS) {
            return;
        }
        // setStackInSlot 是"替换该槽内容"语义，MMCE 的 ItemUtils.consumeAll/insertAll
        // 不走 extractItem/insertItem，而是以它作为消耗与插入的最终落点：
        //   - 完全消耗 → setStackInSlot(slot, EMPTY)
        //   - 部分消耗 → setStackInSlot(slot, 原数量 - 消耗数)
        // 因此这里必须按"目标数量"与"当前数量"的差额调整缓冲区，
        // 否则消耗永远是空操作（材料不减少）或重复插入（刷物品）。
        ItemVariant oldVariant = slotVariants[slot];
        long oldAmount = oldVariant == null ? 0 : buffer.getAmount(oldVariant);

        if (stack.isEmpty()) {
            // 替换为空：扣除该槽映射变体的全部数量。
            if (oldVariant != null && oldAmount > 0) {
                buffer.extract(oldVariant, oldAmount);
            }
            slotVariants[slot] = null;
            return;
        }

        ItemVariant newVariant = new ItemVariant(stack);
        long newCount = stack.getCount();
        if (newVariant.equals(oldVariant)) {
            // 同变体替换：按差额调整。
            long delta = newCount - oldAmount;
            if (delta > 0) {
                ItemStack add = stack.copy();
                add.setCount((int) Math.min(delta, Integer.MAX_VALUE));
                buffer.insert(add);
            } else if (delta < 0) {
                buffer.extract(oldVariant, -delta);
            }
        } else {
            // 不同变体：扣除旧变体全部数量，写入新变体。
            if (oldVariant != null && oldAmount > 0) {
                buffer.extract(oldVariant, oldAmount);
            }
            // 维持"同一变体只映射一个槽"的不变式，避免双份显示。
            for (int i = 0; i < VISIBLE_SLOTS; i++) {
                if (newVariant.equals(slotVariants[i])) {
                    slotVariants[i] = null;
                }
            }
            buffer.insert(stack);
            slotVariants[slot] = newVariant;
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    /**
     * 从缓冲区抽取指定变体的指定数量。
     *
     * @param variant 物品变体
     * @param amount  要抽取的数量
     * @return 实际抽取数量
     */
    public long extract(@Nonnull ItemVariant variant, long amount) {
        return buffer.extract(variant, amount);
    }

    /**
     * 获取缓冲区中指定变体的当前数量。
     */
    public long getAmount(@Nonnull ItemVariant variant) {
        return buffer.getAmount(variant);
    }

    @Override
    public IItemHandlerImpl copy() {
        LongItemBuffer copiedBuffer = new LongItemBuffer();
        copiedBuffer.readFromNBT(bufferToNbt());
        ItemVariant[] copiedVariants = rebuildSlotVariants(copiedBuffer);
        return new LongInputItemHandler(copiedBuffer, copiedVariants);
    }

    @Override
    public IItemHandlerImpl fastCopy() {
        return copy();
    }

    @Nonnull
    @Override
    public net.minecraftforge.items.IItemHandlerModifiable asGUIAccess() {
        return this;
    }

    private int findSlotWithVariant(ItemVariant variant) {
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            if (variant.equals(slotVariants[i])) {
                return i;
            }
        }
        return -1;
    }

    private int findEmptySlot() {
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            if (slotVariants[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 根据当前缓冲区内容重建槽位到物品变体的映射。
     * <p>
     * 当缓冲区被直接修改（例如从 NBT 加载、或被外部代码直接写入）后，
     * 需要调用此方法让 {@link #getStackInSlot} 能够正确暴露物品。
     */
    public void syncWithBuffer() {
        int index = 0;
        java.util.Arrays.fill(slotVariants, null);
        for (Map.Entry<ItemVariant, Long> entry : buffer.snapshot().entrySet()) {
            if (entry.getValue() > 0 && index < VISIBLE_SLOTS) {
                slotVariants[index++] = entry.getKey();
            }
        }
    }

    private ItemVariant[] rebuildSlotVariants(LongItemBuffer targetBuffer) {
        ItemVariant[] variants = new ItemVariant[VISIBLE_SLOTS];
        int index = 0;
        for (Map.Entry<ItemVariant, Long> entry : targetBuffer.snapshot().entrySet()) {
            if (entry.getValue() > 0 && index < VISIBLE_SLOTS) {
                variants[index++] = entry.getKey();
            }
        }
        return variants;
    }

    private net.minecraft.nbt.NBTTagCompound bufferToNbt() {
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        buffer.writeToNBT(tag);
        return tag;
    }
}

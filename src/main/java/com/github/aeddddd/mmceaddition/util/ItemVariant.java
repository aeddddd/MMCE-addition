package com.github.aeddddd.mmceaddition.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 物品变体，用于在 Long 缓冲区中作为键。
 * 包含物品类型、元数据与 NBT，保证 equals/hashCode 稳定。
 */
public final class ItemVariant {

    private final Item item;
    private final int metadata;
    @Nullable
    private final NBTTagCompound nbt;

    public ItemVariant(@Nonnull ItemStack stack) {
        this(stack.getItem(), stack.getMetadata(), stack.getTagCompound());
    }

    public ItemVariant(@Nonnull Item item, int metadata, @Nullable NBTTagCompound nbt) {
        this.item = item;
        this.metadata = metadata;
        this.nbt = nbt == null ? null : nbt.copy();
    }

    @Nonnull
    public Item getItem() {
        return item;
    }

    public int getMetadata() {
        return metadata;
    }

    @Nullable
    public NBTTagCompound getNbt() {
        return nbt == null ? null : nbt.copy();
    }

    /**
     * 创建一个数量为 1 的代表性 ItemStack（用于创建 AEItemStack 等场景）。
     */
    @Nonnull
    public ItemStack toSingleStack() {
        ItemStack stack = new ItemStack(item, 1, metadata);
        if (nbt != null) {
            stack.setTagCompound(nbt.copy());
        }
        return stack;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemVariant)) return false;
        ItemVariant that = (ItemVariant) o;
        return metadata == that.metadata &&
                item.equals(that.item) &&
                Objects.equals(nbt, that.nbt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(item, metadata, nbt);
    }

    @Override
    public String toString() {
        return "ItemVariant{" + item.getRegistryName() + "/" + metadata + ", nbt=" + nbt + '}';
    }
}

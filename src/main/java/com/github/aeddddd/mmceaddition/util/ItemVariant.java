package com.github.aeddddd.mmceaddition.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 物品变体。
 * <p>
 * 在 Minecraft 中，同一个 Item 加上不同的 metadata 或 NBT 就是不同的“物品变体”。
 * 例如：白色羊毛和黑色羊毛是同一个 Item，但 metadata 不同；
 * 附魔书和未附魔书 metadata 相同但 NBT 不同。
 * <p>
 * 本类把 Item + metadata + NBT 封装为一个值对象，重写 equals/hashCode，
 * 使其可以作为 {@link java.util.HashMap} 的键，用于 Long 缓冲区按变体聚合数量。
 */
public final class ItemVariant {

    private final Item item;
    private final int metadata;
    @Nullable
    private final NBTTagCompound nbt;

    /**
     * 从 ItemStack 构造变体。数量信息会被丢弃。
     */
    public ItemVariant(@Nonnull ItemStack stack) {
        this(stack.getItem(), stack.getMetadata(), stack.getTagCompound());
    }

    /**
     * 直接指定 Item、metadata、NBT 构造变体。
     */
    public ItemVariant(@Nonnull Item item, int metadata, @Nullable NBTTagCompound nbt) {
        this.item = item;
        this.metadata = metadata;
        // NBT 是可变对象，这里拷贝一份防止外部修改影响 hashCode/equals。
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
     * 创建一个数量为 1 的代表性 ItemStack。
     * <p>
     * 用于创建 AEItemStack 等只需要“是什么物品”而不需要数量的场景。
     *
     * @return 数量为 1 的 ItemStack
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

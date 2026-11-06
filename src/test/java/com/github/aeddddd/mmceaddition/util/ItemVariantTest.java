package com.github.aeddddd.mmceaddition.util;

import com.github.aeddddd.mmceaddition.MinecraftBootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemVariant} 的值语义测试。
 * <p>
 * 它是 {@link LongItemBuffer} 的 HashMap 键：一旦 equals/hashCode 或 NBT 拷贝出了问题，
 * 缓冲就会出现"数量查不到"或"同一种物品分成两份计数"的守恒事故。
 */
class ItemVariantTest extends MinecraftBootstrap {

    @Test
    @DisplayName("变体按 物品 + metadata + NBT 相等，与数量无关")
    void equals_ignoresCountAndComparesItemMetadataAndNbt() {
        ItemVariant single = new ItemVariant(stack(Items.STICK, 1));
        ItemVariant huge = new ItemVariant(stack(Items.STICK, Integer.MAX_VALUE));

        assertEquals(single, huge, "数量不应参与变体相等性");
        assertEquals(single.hashCode(), huge.hashCode(), "相等的变体必须有相同的 hashCode（HashMap 键契约）");
        assertEquals(single, single);
        assertFalse(single.equals(null), "不得对 null 抛异常");
        assertFalse(single.equals("not a variant"), "不得对其它类型抛异常");
    }

    @Test
    @DisplayName("metadata 或 NBT 不同即为不同变体")
    void equals_distinguishesMetadataAndNbt() {
        ItemVariant plain = new ItemVariant(Items.COAL, 0, null);
        ItemVariant charcoal = new ItemVariant(Items.COAL, 1, null);
        ItemVariant tagged = taggedVariant(Items.COAL, 1);
        ItemVariant otherTag = taggedVariant(Items.COAL, 2);

        assertNotEquals(plain, charcoal, "不同 metadata 是不同的物品变体");
        assertNotEquals(plain, tagged, "有无 NBT 是不同的物品变体");
        assertNotEquals(tagged, otherTag, "NBT 内容不同是不同的物品变体");
        assertEquals(charcoal, new ItemVariant(new ItemStack(Items.COAL, 3, 1)),
                "metadata 相同、数量不同应视为同一变体");
    }

    @Test
    @DisplayName("NBT 防御性拷贝：外部改动不会影响已建好的变体")
    void nbtIsDefensivelyCopied() {
        ItemStack stack = taggedStack(Items.COAL, 7, 1);
        ItemVariant variant = new ItemVariant(stack);

        // 改动源 ItemStack 的 NBT 之后，变体的键不应发生变化（否则 HashMap 会丢失这条库存）
        stack.getTagCompound().setInteger("probe", 99);
        assertEquals(taggedVariant(Items.COAL, 7), variant, "变体必须持有 NBT 的副本");

        // getNbt() 返回的也必须是副本
        NBTTagCompound exposed = variant.getNbt();
        exposed.setInteger("probe", 123);
        assertEquals(taggedVariant(Items.COAL, 7), variant, "getNbt() 不得把内部 NBT 暴露出去");
    }

    @Test
    @DisplayName("toSingleStack 产出数量 1 且保留 NBT 的代表性堆")
    void toSingleStack_hasCountOneAndKeepsNbt() {
        ItemVariant variant = new ItemVariant(stack(Items.STICK, Integer.MAX_VALUE));
        ItemStack single = variant.toSingleStack();

        assertEquals(Items.STICK, single.getItem());
        assertEquals(1, single.getCount(), "代表性堆的数量恒为 1");
        assertNull(single.getTagCompound(), "无 NBT 的变体不应凭空多出 NBT");

        ItemVariant tagged = taggedVariant(Items.COAL, 5);
        ItemStack taggedSingle = tagged.toSingleStack();
        assertEquals(1, taggedSingle.getCount());
        assertEquals(5, taggedSingle.getTagCompound().getInteger("probe"), "NBT 必须被带上");
        assertEquals(tagged, new ItemVariant(taggedSingle), "往返后仍是同一变体");
    }

    @Test
    @DisplayName("变体可以在 HashMap 里按键命中")
    void worksAsHashMapKey() {
        java.util.Map<ItemVariant, Long> map = new java.util.HashMap<>();
        map.put(new ItemVariant(stack(Items.STICK, 1)), 42L);

        assertTrue(map.containsKey(new ItemVariant(stack(Items.STICK, 64))), "同变体应能查到");
        assertFalse(map.containsKey(taggedVariant(Items.STICK, 1)), "NBT 不同不应命中");
    }
}

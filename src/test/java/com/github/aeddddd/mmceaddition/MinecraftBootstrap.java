package com.github.aeddddd.mmceaddition;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;

/**
 * 测试基类：在跑测试前引导 Minecraft 的注册表。
 * <p>
 * 本模组的核心逻辑（Long 缓冲、槽位记账）大量依赖 {@link ItemStack}，
 * 而 ItemStack 又依赖 {@code Item.REGISTRY} 等静态注册表；
 * {@link Bootstrap#register()} 能在无 World / 无 Minecraft 实例的前提下把这些表建起来。
 * <p>
 * 幂等：同一个 JVM 里所有测试类共享一次注册（{@code register()} 本身也自带守卫，
 * 这里再包一层静态标志，保证多测试类并存时只做一次、且不会重复初始化报错）。
 */
public abstract class MinecraftBootstrap {

    private static boolean bootstrapped;

    @BeforeAll
    public static synchronized void bootstrapMinecraft() {
        if (bootstrapped) {
            return;
        }
        Bootstrap.register();
        bootstrapped = true;
    }

    /** 造一个指定数量的物品堆（数量可以超过 maxStackSize，用于测试 Long 缓冲）。 */
    protected static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    /**
     * 造一个带唯一 NBT 标记的物品堆，用于在同一物品上生成大量互不相同的"物品变体"。
     */
    protected static ItemStack taggedStack(Item item, int tagId, int count) {
        ItemStack stack = new ItemStack(item, count);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("probe", tagId);
        stack.setTagCompound(tag);
        return stack;
    }

    /** 与 {@link #taggedStack} 同构的变体键。 */
    protected static com.github.aeddddd.mmceaddition.util.ItemVariant taggedVariant(Item item, int tagId) {
        return new com.github.aeddddd.mmceaddition.util.ItemVariant(taggedStack(item, tagId, 1));
    }

    /** 物品的"裸"变体键（无 metadata、无 NBT）。 */
    protected static com.github.aeddddd.mmceaddition.util.ItemVariant variantOf(Item item) {
        return new com.github.aeddddd.mmceaddition.util.ItemVariant(stack(item, 1));
    }

    /** 常用物品：探针用的基础物品（已注册，数量语义与 NBT 无关）。 */
    protected static Item probeItem() {
        return Items.STICK;
    }
}

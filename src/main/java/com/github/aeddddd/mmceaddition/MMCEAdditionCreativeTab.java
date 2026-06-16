package com.github.aeddddd.mmceaddition;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;

/**
 * 创造模式物品栏（Creative Tab）。
 * <p>
 * 每个模组通常会创建一个自己的创造栏，把本模组的方块/物品集中放在一起，方便玩家查找。
 * 这里为了简单，用铁锭作为栏目标识；实际项目中可以换成自己的方块物品。
 */
public class MMCEAdditionCreativeTab {

    /**
     * 创造栏实例。
     * <p>
     * CreativeTabs 的构造函数参数是创造栏的唯一标识名，会显示在语言文件中（itemGroup.modid）。
     * createIcon() 返回的 ItemStack 会显示在创造模式物品栏的图标位置。
     */
    public static final CreativeTabs TAB = new CreativeTabs(MMCEAddition.MODID) {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(Items.IRON_INGOT);
        }
    };
}

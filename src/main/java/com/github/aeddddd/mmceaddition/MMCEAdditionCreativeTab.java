package com.github.aeddddd.mmceaddition;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;

/**
 * MMCE Addition 创造模式物品栏。
 */
public class MMCEAdditionCreativeTab {

    public static final CreativeTabs TAB = new CreativeTabs(MMCEAddition.MODID) {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(Items.IRON_INGOT);
        }
    };
}

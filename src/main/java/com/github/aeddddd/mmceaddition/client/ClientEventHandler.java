package com.github.aeddddd.mmceaddition.client;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.List;

/**
 * 客户端事件处理器：模型注册与物品提示。
 */
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID, value = Side.CLIENT)
public class ClientEventHandler {

    @SubscribeEvent
    public static void onModelRegister(ModelRegistryEvent event) {
        registerItemModel(Item.getItemFromBlock(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS));
        registerItemModel(Item.getItemFromBlock(RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH));
    }

    private static void registerItemModel(Item item) {
        if (item == null) return;
        ModelLoader.setCustomModelResourceLocation(item, 0,
                new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        List<String> tooltip = event.getToolTip();
        if (item == Item.getItemFromBlock(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS)) {
            addMultiLineTooltip(tooltip, "tooltip.mmceaddition.me_async_item_output_bus");
        } else if (item == Item.getItemFromBlock(RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH)) {
            addMultiLineTooltip(tooltip, "tooltip.mmceaddition.me_async_fluid_output_hatch");
        }
    }

    private static void addMultiLineTooltip(List<String> tooltip, String baseKey) {
        for (int i = 0; i < 4; i++) {
            String key = baseKey + ".line" + i;
            String text = I18n.format(key, MMCEAdditionConfig.injectionInterval);
            if (!text.equals(key)) {
                tooltip.add(text);
            }
        }
    }
}

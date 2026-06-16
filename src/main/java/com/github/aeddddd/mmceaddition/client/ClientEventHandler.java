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
 * 客户端事件处理器。
 * <p>
 * 使用 {@code @Mod.EventBusSubscriber(modid = ..., value = Side.CLIENT)} 自动注册到 Forge 客户端事件总线，
 * 无需在代理中手动注册。
 * <p>
 * 负责：
 * <ul>
 *   <li>为 ItemBlock 显式注册模型路径（解决某些资源包/优化模组的模型加载问题）</li>
 *   <li>为物品添加 Tooltip 性能说明</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID, value = Side.CLIENT)
public class ClientEventHandler {

    /**
     * 模型注册事件。
     * <p>
     * 虽然 Forge 默认会根据 Item 注册名自动查找模型，
     * 但显式调用 ModelLoader.setCustomModelResourceLocation 可以避免 VintageFix 等优化模组
     * 在动态资源加载时找不到模型的问题。
     */
    @SubscribeEvent
    public static void onModelRegister(ModelRegistryEvent event) {
        registerItemModel(Item.getItemFromBlock(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS));
        registerItemModel(Item.getItemFromBlock(RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH));
    }

    /**
     * 为单个 Item 注册模型。
     *
     * @param item 要注册模型的物品
     */
    private static void registerItemModel(Item item) {
        if (item == null) return;
        ModelLoader.setCustomModelResourceLocation(item, 0,
                new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }

    /**
     * 物品 Tooltip 事件。
     * <p>
     * 当玩家把鼠标悬停在物品上时触发。我们为本模组的两个方块物品添加多行说明。
     */
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

    /**
     * 读取语言文件中 line0 ~ line3 的四行 tooltip 并添加到列表。
     * <p>
     * 语言键格式：baseKey.line0、baseKey.line1...
     * I18n.format 的第二个参数是 injectionInterval，可以在 lang 文件里用 %d 显示当前间隔。
     *
     * @param tooltip 要追加的 tooltip 列表
     * @param baseKey 基础语言键
     */
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

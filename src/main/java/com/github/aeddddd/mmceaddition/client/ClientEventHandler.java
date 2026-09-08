package com.github.aeddddd.mmceaddition.client;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import hellfirepvp.modularmachinery.common.block.BlockMachineComponent;
import hellfirepvp.modularmachinery.common.data.Config;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.ColorHandlerEvent;
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
        registerItemModel(Item.getItemFromBlock(RegistryHandler.ME_PATTERN_ASSEMBLY));
        registerItemModel(Item.getItemFromBlock(RegistryHandler.ME_OUTPUT_ASSEMBLY));
        registerItemModel(Item.getItemFromBlock(RegistryHandler.INPUT_ASSEMBLY));
        registerItemModel(Item.getItemFromBlock(RegistryHandler.VIRTUAL_ASSEMBLER));
        registerItemModel(Item.getItemFromBlock(RegistryHandler.VIRTUAL_PARALLEL_HATCH));
        registerItemModel(RegistryHandler.MACHINE_DATA);
        registerItemModel(RegistryHandler.HATCH_ADMIN_TOOL);
    }

    /**
     * 方块染色注册。
     * <p>
     * MMCE 的成型变色机制：结构成型时控制器把机器 JSON 的 color 写入结构内所有
     * {@code ColorableMachineTile}（casingColor，NBT 持久化 + 网络同步 + block event 刷新渲染），
     * 客户端再经 {@code IBlockColor} 对模型 tintindex=1 的灰色外壳面做颜色乘算。
     * <p>
     * MMCE 只为自家方块注册颜色 handler，本模组的方块（均继承 BlockMachineComponent，
     * 已实现 getColorMultiplier）需要在此自行注册，否则永远显示未染色的默认外壳。
     */
    @SubscribeEvent
    public static void onBlockColors(ColorHandlerEvent.Block event) {
        for (BlockMachineComponent block : new BlockMachineComponent[]{
                RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS,
                RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH,
                RegistryHandler.ME_PATTERN_ASSEMBLY,
                RegistryHandler.ME_OUTPUT_ASSEMBLY,
                RegistryHandler.INPUT_ASSEMBLY,
                RegistryHandler.VIRTUAL_ASSEMBLER,
                RegistryHandler.VIRTUAL_PARALLEL_HATCH
        }) {
            event.getBlockColors().registerBlockColorHandler(block::getColorMultiplier, block);
        }
    }

    /**
     * 物品染色注册。
     * <p>
     * 机器数据的第二层（白色电路走线贴图）按机器注册名 hash 动态着色，
     * 同机器同色、不同机器可区分，无需为每种机器单独绘制贴图。
     * <p>
     * 仓室方块的 ItemBlock 与 MMCE 的 ItemBlockMachineComponent 行为一致：
     * 背包/JEI/手持中恒显示默认机器颜色（Config.machineColor）。
     */
    @SubscribeEvent
    public static void onItemColors(ColorHandlerEvent.Item event) {
        event.getItemColors().registerItemColorHandler((stack, tintIndex) -> {
            if (tintIndex == 1) {
                return ItemMachineData.tintFor(ItemMachineData.getMachineName(stack));
            }
            return 0xFFFFFFFF;
        }, RegistryHandler.MACHINE_DATA);

        // 仓室方块 ItemBlock：与 MMCE ItemBlockMachineComponent 一致，物品形态恒显示默认机器颜色
        event.getItemColors().registerItemColorHandler((stack, tintIndex) -> Config.machineColor,
                Item.getItemFromBlock(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS),
                Item.getItemFromBlock(RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH),
                Item.getItemFromBlock(RegistryHandler.ME_PATTERN_ASSEMBLY),
                Item.getItemFromBlock(RegistryHandler.ME_OUTPUT_ASSEMBLY),
                Item.getItemFromBlock(RegistryHandler.INPUT_ASSEMBLY),
                Item.getItemFromBlock(RegistryHandler.VIRTUAL_ASSEMBLER),
                Item.getItemFromBlock(RegistryHandler.VIRTUAL_PARALLEL_HATCH));
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
     * 当玩家把鼠标悬停在物品上时触发。Tooltip 分为两个分区：
     * <ul>
     *   <li>功能分区：该方块自身提供的功能（连接网络、输入/输出、样板槽等）</li>
     *   <li>结构兼容分区：该方块可被 MMCE 结构识别为哪些原版仓室
     *      （与 {@code BlockInformationMixin} 的匹配逻辑一一对应，受配置开关控制）</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        Item item = stack.getItem();
        List<String> tooltip = event.getToolTip();
        if (item == Item.getItemFromBlock(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.me_async_item_output_bus");
            addCompatSection(tooltip, MMCEAdditionConfig.enableMEItemBusCompat, "item_output_bus");
        } else if (item == Item.getItemFromBlock(RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.me_async_fluid_output_hatch");
            addCompatSection(tooltip, MMCEAdditionConfig.enableMEFluidBusCompat, "fluid_output_hatch");
        } else if (item == Item.getItemFromBlock(RegistryHandler.ME_PATTERN_ASSEMBLY)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.me_pattern_assembly");
            addCompatSection(tooltip, MMCEAdditionConfig.enableMEPatternAssemblyCompat,
                    "item_input_bus", "item_output_bus", "fluid_input_hatch", "fluid_output_hatch",
                    "energy_input_hatch", "pattern_provider");
        } else if (item == Item.getItemFromBlock(RegistryHandler.ME_OUTPUT_ASSEMBLY)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.me_output_assembly");
            addCompatSection(tooltip, MMCEAdditionConfig.enableMEOutputAssemblyCompat,
                    "item_input_bus", "item_output_bus", "fluid_input_hatch", "fluid_output_hatch",
                    "energy_input_hatch", "energy_output_hatch");
        } else if (item == Item.getItemFromBlock(RegistryHandler.INPUT_ASSEMBLY)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.input_assembly");
            addCompatSection(tooltip, MMCEAdditionConfig.enableInputAssemblyCompat,
                    "item_input_bus", "item_output_bus", "fluid_input_hatch", "fluid_output_hatch");
        } else if (item == Item.getItemFromBlock(RegistryHandler.VIRTUAL_ASSEMBLER)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.virtual_assembler");
        } else if (item == Item.getItemFromBlock(RegistryHandler.VIRTUAL_PARALLEL_HATCH)) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.virtual_parallel_hatch");
            addCompatSection(tooltip, MMCEAdditionConfig.enableVirtualHatchCompat,
                    "item_input_bus", "item_output_bus", "fluid_input_hatch", "fluid_output_hatch",
                    "energy_input_hatch", "energy_output_hatch", "pattern_provider");
        } else if (item == RegistryHandler.HATCH_ADMIN_TOOL) {
            addFunctionLines(tooltip, "tooltip.mmceaddition.hatch_admin_tool");
        }
    }

    /**
     * 功能分区：先输出"功能:"标题，再按 line0 ~ line7 顺序输出功能行。
     * <p>
     * 语言键格式：baseKey.line0、baseKey.line1... 遇到缺失的键即停止。
     *
     * @param tooltip 要追加的 tooltip 列表
     * @param baseKey 基础语言键
     */
    private static void addFunctionLines(List<String> tooltip, String baseKey) {
        tooltip.add(I18n.format("tooltip.mmceaddition.features.header"));
        for (int i = 0; i < 8; i++) {
            String key = baseKey + ".line" + i;
            String text = I18n.format(key);
            if (text.equals(key) || text.isEmpty()) {
                break;
            }
            tooltip.add(text);
        }
    }

    /**
     * 结构兼容分区：输出"可被识别为以下仓室:"标题与仓室列表。
     * <p>
     * 仅当对应配置开关启用时显示；开关关闭时结构匹配同样不生效，tooltip 一并隐藏。
     *
     * @param tooltip    要追加的 tooltip 列表
     * @param compatEnabled 对应的结构兼容配置开关
     * @param compatKeys 仓室语言键后缀（tooltip.mmceaddition.compat.&lt;key&gt;）
     */
    private static void addCompatSection(List<String> tooltip, boolean compatEnabled, String... compatKeys) {
        if (!compatEnabled || compatKeys.length == 0) {
            return;
        }
        tooltip.add(I18n.format("tooltip.mmceaddition.compat.header"));
        for (String key : compatKeys) {
            tooltip.add(I18n.format("tooltip.mmceaddition.compat." + key));
        }
    }
}

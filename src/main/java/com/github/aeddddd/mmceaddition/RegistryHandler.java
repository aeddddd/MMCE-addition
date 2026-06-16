package com.github.aeddddd.mmceaddition;

import com.github.aeddddd.mmceaddition.block.BlockMEAsyncFluidOutputHatch;
import com.github.aeddddd.mmceaddition.block.BlockMEAsyncItemOutputBus;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncFluidOutputHatch;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

/**
 * 注册处理器。
 * <p>
 * 在 Forge 1.12.2 中，方块（Block）、物品（Item）、TileEntity 都需要在对应的事件中注册。
 * 这个类订阅了 {@link RegistryEvent.Register<Block>} 和 {@link RegistryEvent.Register<Item>}，
 * 在预初始化阶段完成所有注册工作。
 * <p>
 * 注意：TileEntity 虽然不是通过 RegistryEvent 注册，但通常在 Block 注册事件触发时调用
 * {@link GameRegistry#registerTileEntity(Class, ResourceLocation)} 进行注册。
 */
public class RegistryHandler {

    /**
     * ME 异步物品输出总线方块实例。
     * <p>
     * 使用静态 final 字段持有实例，方便其他类直接引用，避免重复创建。
     * 注册时会把它加入 Forge 的 Block 注册表。
     */
    public static final BlockMEAsyncItemOutputBus ME_ASYNC_ITEM_OUTPUT_BUS = new BlockMEAsyncItemOutputBus();

    /**
     * ME 异步流体输出仓方块实例。
     */
    public static final BlockMEAsyncFluidOutputHatch ME_ASYNC_FLUID_OUTPUT_HATCH = new BlockMEAsyncFluidOutputHatch();

    /**
     * 方块注册事件处理器。
     * <p>
     * Forge 在加载阶段会触发 {@link RegistryEvent.Register<Block>}，所有方块必须在这个事件里注册。
     * 如果方块带有 TileEntity，通常也在这里一起注册 TileEntity。
     *
     * @param event 方块注册事件
     */
    @SubscribeEvent
    public void onBlockRegister(RegistryEvent.Register<Block> event) {
        event.getRegistry().registerAll(
                ME_ASYNC_ITEM_OUTPUT_BUS,
                ME_ASYNC_FLUID_OUTPUT_HATCH
        );

        // 注册 TileEntity。
        // 第一个参数是 TileEntity 的 Class 对象；
        // 第二个参数是 ResourceLocation，作为 TileEntity 的全局唯一 ID，格式为 modid:name。
        GameRegistry.registerTileEntity(TileMEAsyncItemOutputBus.class,
                new ResourceLocation(MMCEAddition.MODID, "me_async_item_output_bus"));
        GameRegistry.registerTileEntity(TileMEAsyncFluidOutputHatch.class,
                new ResourceLocation(MMCEAddition.MODID, "me_async_fluid_output_hatch"));
    }

    /**
     * 物品注册事件处理器。
     * <p>
     * 每个方块默认不会自动拥有物品形态。要让方块能被玩家拿在手上、放进背包、在创造栏显示，
     * 需要为其注册一个 {@link ItemBlock}，并把注册名设为与方块相同。
     *
     * @param event 物品注册事件
     */
    @SubscribeEvent
    public void onItemRegister(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(
                // ItemBlock 是方块对应的物品。玩家手持、JEI、创造栏显示的都是这个 ItemBlock。
                new ItemBlock(ME_ASYNC_ITEM_OUTPUT_BUS)
                        // 物品注册名必须与方块注册名一致，这样游戏才能把物品和方块关联起来。
                        .setRegistryName(ME_ASYNC_ITEM_OUTPUT_BUS.getRegistryName()),
                new ItemBlock(ME_ASYNC_FLUID_OUTPUT_HATCH)
                        .setRegistryName(ME_ASYNC_FLUID_OUTPUT_HATCH.getRegistryName())
        );
    }
}

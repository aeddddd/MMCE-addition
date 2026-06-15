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
 * 处理方块、物品与 TileEntity 的注册。
 */
public class RegistryHandler {

    public static final BlockMEAsyncItemOutputBus ME_ASYNC_ITEM_OUTPUT_BUS = new BlockMEAsyncItemOutputBus();
    public static final BlockMEAsyncFluidOutputHatch ME_ASYNC_FLUID_OUTPUT_HATCH = new BlockMEAsyncFluidOutputHatch();

    @SubscribeEvent
    public void onBlockRegister(RegistryEvent.Register<Block> event) {
        event.getRegistry().registerAll(
                ME_ASYNC_ITEM_OUTPUT_BUS,
                ME_ASYNC_FLUID_OUTPUT_HATCH
        );

        GameRegistry.registerTileEntity(TileMEAsyncItemOutputBus.class,
                new ResourceLocation(MMCEAddition.MODID, "me_async_item_output_bus"));
        GameRegistry.registerTileEntity(TileMEAsyncFluidOutputHatch.class,
                new ResourceLocation(MMCEAddition.MODID, "me_async_fluid_output_hatch"));
    }

    @SubscribeEvent
    public void onItemRegister(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(
                new ItemBlock(ME_ASYNC_ITEM_OUTPUT_BUS)
                        .setRegistryName(ME_ASYNC_ITEM_OUTPUT_BUS.getRegistryName()),
                new ItemBlock(ME_ASYNC_FLUID_OUTPUT_HATCH)
                        .setRegistryName(ME_ASYNC_FLUID_OUTPUT_HATCH.getRegistryName())
        );
    }
}

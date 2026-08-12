package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import javax.annotation.Nullable;

/**
 * ME 样板总成 GUI 处理器。
 */
public class GuiHandler implements IGuiHandler {

    public static final int GUI_ME_PATTERN_ASSEMBLY = 0;

    @Nullable
    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_ME_PATTERN_ASSEMBLY) {
            TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
            if (tile instanceof TileMEPatternAssembly) {
                return new ContainerMEPatternAssembly((TileMEPatternAssembly) tile, player);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_ME_PATTERN_ASSEMBLY) {
            TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
            if (tile instanceof TileMEPatternAssembly) {
                return new GuiMEPatternAssembly((TileMEPatternAssembly) tile, new ContainerMEPatternAssembly((TileMEPatternAssembly) tile, player));
            }
        }
        return null;
    }
}

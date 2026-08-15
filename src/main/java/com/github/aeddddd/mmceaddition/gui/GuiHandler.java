package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualAssembler;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import javax.annotation.Nullable;

/**
 * GUI 处理器。
 */
public class GuiHandler implements IGuiHandler {

    public static final int GUI_ME_PATTERN_ASSEMBLY = 0;
    public static final int GUI_VIRTUAL_ASSEMBLER = 1;
    public static final int GUI_VIRTUAL_PARALLEL_HATCH = 2;

    @Nullable
    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
        if (id == GUI_ME_PATTERN_ASSEMBLY) {
            if (tile instanceof TileMEPatternAssembly) {
                return new ContainerMEPatternAssembly((TileMEPatternAssembly) tile, player);
            }
        } else if (id == GUI_VIRTUAL_ASSEMBLER) {
            if (tile instanceof TileVirtualAssembler) {
                return new ContainerVirtualAssembler((TileVirtualAssembler) tile, player);
            }
        } else if (id == GUI_VIRTUAL_PARALLEL_HATCH) {
            if (tile instanceof TileVirtualParallelHatch) {
                return new ContainerVirtualParallelHatch((TileVirtualParallelHatch) tile, player);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
        if (id == GUI_ME_PATTERN_ASSEMBLY) {
            if (tile instanceof TileMEPatternAssembly) {
                return new GuiMEPatternAssembly((TileMEPatternAssembly) tile, new ContainerMEPatternAssembly((TileMEPatternAssembly) tile, player));
            }
        } else if (id == GUI_VIRTUAL_ASSEMBLER) {
            if (tile instanceof TileVirtualAssembler) {
                return new GuiVirtualAssembler(new ContainerVirtualAssembler((TileVirtualAssembler) tile, player));
            }
        } else if (id == GUI_VIRTUAL_PARALLEL_HATCH) {
            if (tile instanceof TileVirtualParallelHatch) {
                return new GuiVirtualParallelHatch((TileVirtualParallelHatch) tile, new ContainerVirtualParallelHatch((TileVirtualParallelHatch) tile, player));
            }
        }
        return null;
    }
}

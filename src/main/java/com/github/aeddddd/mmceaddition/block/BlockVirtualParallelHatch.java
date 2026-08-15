package com.github.aeddddd.mmceaddition.block;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import com.github.aeddddd.mmceaddition.gui.GuiHandler;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import hellfirepvp.modularmachinery.common.block.BlockMachineComponent;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 虚拟并行仓方块。
 * <p>
 * 可替换机器 JSON 中任意仓室位置（经 {@code BlockInformationMixin} 兼容）。
 * 放入与机器匹配的机器数据后提供独立乘区并行度。
 * 破坏时仓内机器数据随方块 NBT 保存（与 ME 样板总成一致）。
 */
public class BlockVirtualParallelHatch extends BlockMachineComponent {

    public BlockVirtualParallelHatch() {
        super(Material.IRON);
        setTranslationKey(MMCEAddition.MODID + ".virtual_parallel_hatch");
        setRegistryName(MMCEAddition.MODID, "virtual_parallel_hatch");
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
        setHardness(4.0f);
        setResistance(20.0f);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileVirtualParallelHatch) {
                player.openGui(MMCEAddition.instance, GuiHandler.GUI_VIRTUAL_PARALLEL_HATCH, world, pos.getX(), pos.getY(), pos.getZ());
            }
        }
        return true;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return true;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @Override
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileVirtualParallelHatch();
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileVirtualParallelHatch();
    }

    @Override
    public void dropBlockAsItemWithChance(World world, BlockPos pos, IBlockState state, float chance, int fortune) {
        // 破坏时把仓内机器数据写进掉落方块的 NBT
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileVirtualParallelHatch) {
            ItemStack drop = new ItemStack(this);
            ItemStack data = ((TileVirtualParallelHatch) tile).getDataStack();
            if (!data.isEmpty()) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setTag("data", data.writeToNBT(new NBTTagCompound()));
                drop.setTagCompound(tag);
            }
            spawnAsEntity(world, pos, drop);
        } else {
            super.dropBlockAsItemWithChance(world, pos, state, chance, fortune);
        }
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileVirtualParallelHatch && stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag.hasKey("data")) {
                ((TileVirtualParallelHatch) tile).setDataStack(new ItemStack(tag.getCompoundTag("data")));
            }
        }
    }
}

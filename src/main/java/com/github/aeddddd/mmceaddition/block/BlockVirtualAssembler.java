package com.github.aeddddd.mmceaddition.block;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import com.github.aeddddd.mmceaddition.gui.GuiHandler;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualAssembler;
import hellfirepvp.modularmachinery.common.block.BlockMachineComponent;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
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
 * 虚拟装配台方块。
 * <p>
 * 单方块机器：把 MMCE 多方块机器（控制器 + 全部结构方块，不含仓室/蓝图）
 * 折算为材料清单，消耗内部缓存材料产出机器数据。
 * 破坏时缓存内容随方块 NBT 保存（与 ME 样板总成一致）。
 */
public class BlockVirtualAssembler extends BlockMachineComponent {

    public BlockVirtualAssembler() {
        super(Material.IRON);
        setTranslationKey(MMCEAddition.MODID + ".virtual_assembler");
        setRegistryName(MMCEAddition.MODID, "virtual_assembler");
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
        setHardness(4.0f);
        setResistance(20.0f);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileVirtualAssembler) {
                player.openGui(MMCEAddition.instance, GuiHandler.GUI_VIRTUAL_ASSEMBLER, world, pos.getX(), pos.getY(), pos.getZ());
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
        return new TileVirtualAssembler();
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileVirtualAssembler();
    }

    @Override
    public void dropBlockAsItemWithChance(World world, BlockPos pos, IBlockState state, float chance, int fortune) {
        // 破坏时保存全部缓存/输出到掉落物 NBT
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileVirtualAssembler) {
            ItemStack drop = new ItemStack(this);
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagCompound tileTag = tile.writeToNBT(new NBTTagCompound());
            // 去掉坐标等与内容无关的字段
            tileTag.removeTag("x");
            tileTag.removeTag("y");
            tileTag.removeTag("z");
            tileTag.removeTag("id");
            if (!tileTag.isEmpty()) {
                drop.setTagCompound(tileTag);
            }
            spawnAsEntity(world, pos, drop);
        } else {
            super.dropBlockAsItemWithChance(world, pos, state, chance, fortune);
        }
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, net.minecraft.entity.EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileVirtualAssembler && stack.hasTagCompound()) {
            tile.readFromNBT(mergePos(stack.getTagCompound(), pos));
        }
    }

    private static NBTTagCompound mergePos(NBTTagCompound tag, BlockPos pos) {
        NBTTagCompound copy = tag.copy();
        copy.setInteger("x", pos.getX());
        copy.setInteger("y", pos.getY());
        copy.setInteger("z", pos.getZ());
        return copy;
    }
}

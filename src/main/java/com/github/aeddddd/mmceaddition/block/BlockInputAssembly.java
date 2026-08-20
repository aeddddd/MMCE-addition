package com.github.aeddddd.mmceaddition.block;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import com.github.aeddddd.mmceaddition.tile.TileInputAssembly;
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
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 输入总成仓方块。
 * <p>
 * 超大容量（Long 上限）物品/流体输入缓冲，不连 AE 网络；
 * 被机器同时识别为物品输入总线与流体输入仓。
 * 右键手持物品直接存入，空手右键查看缓冲量。
 */
public class BlockInputAssembly extends BlockMachineComponent {

    public BlockInputAssembly() {
        super(Material.IRON);
        setTranslationKey(MMCEAddition.MODID + ".input_assembly");
        setRegistryName(MMCEAddition.MODID, "input_assembly");
        setHardness(2.0f);
        setResistance(10.0f);
        setHarvestLevel("pickaxe", 1);
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            return true;
        }
        TileEntity te = worldIn.getTileEntity(pos);
        if (!(te instanceof TileInputAssembly)) {
            return true;
        }
        TileInputAssembly assembly = (TileInputAssembly) te;
        ItemStack held = playerIn.getHeldItem(hand);
        if (!held.isEmpty()) {
            // 手持物品右键：存入物品缓冲，返还放不下的部分
            ItemStack remainder = assembly.insertItemToBuffer(held);
            playerIn.setHeldItem(hand, remainder);
            playerIn.sendMessage(new TextComponentString(
                    "Input Assembly: stored, now " + assembly.getBufferedItemAmount() + " items buffered"));
        } else {
            playerIn.sendMessage(new TextComponentString(
                    "Input Assembly: " + assembly.getBufferedItemAmount() + " items, "
                            + assembly.getBufferedFluidAmount() + " mB buffered"));
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

    @Override
    @Nullable
    public TileEntity createTileEntity(@Nonnull World world, @Nonnull IBlockState state) {
        return new TileInputAssembly();
    }

    @Override
    @Nullable
    public TileEntity createNewTileEntity(@Nonnull World worldIn, int meta) {
        return new TileInputAssembly();
    }

    @Override
    public void getDrops(@Nonnull NonNullList<ItemStack> drops, @Nonnull IBlockAccess world,
                         @Nonnull BlockPos pos, @Nonnull IBlockState state, int fortune) {
        TileEntity te = world.getTileEntity(pos);
        ItemStack dropped = new ItemStack(this);
        if (te instanceof TileInputAssembly) {
            NBTTagCompound tag = new NBTTagCompound();
            ((TileInputAssembly) te).writeBuffersToNBT(tag);
            if (!tag.isEmpty()) {
                dropped.setTagCompound(tag);
            }
        }
        drops.add(dropped);
    }

    @Override
    public void onBlockPlacedBy(World worldIn, BlockPos pos, IBlockState state,
                                net.minecraft.entity.EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(worldIn, pos, state, placer, stack);
        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TileInputAssembly && stack.hasTagCompound()) {
            ((TileInputAssembly) te).readBuffersFromNBT(stack.getTagCompound());
        }
    }
}

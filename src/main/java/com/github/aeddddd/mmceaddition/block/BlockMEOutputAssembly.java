package com.github.aeddddd.mmceaddition.block;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import com.github.aeddddd.mmceaddition.tile.TileMEOutputAssembly;
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
 * ME 输出总成仓方块。
 * <p>
 * 单方块同时充当物品输出总线、流体输出仓与能源仓（输入/输出），
 * 产出经缓冲批量回注 AE 网络；能源经 AE2Enhanced 网络 RF 通道双向流动。
 */
public class BlockMEOutputAssembly extends BlockMachineComponent {

    public BlockMEOutputAssembly() {
        super(Material.IRON);
        setTranslationKey(MMCEAddition.MODID + ".me_output_assembly");
        setRegistryName(MMCEAddition.MODID, "me_output_assembly");
        setHardness(2.0f);
        setResistance(10.0f);
        setHarvestLevel("pickaxe", 1);
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            TileEntity te = worldIn.getTileEntity(pos);
            if (te instanceof TileMEOutputAssembly) {
                TileMEOutputAssembly assembly = (TileMEOutputAssembly) te;
                playerIn.sendMessage(new TextComponentString(
                        "ME Output Assembly: " + assembly.getBufferedItemAmount() + " items, "
                                + assembly.getBufferedFluidAmount() + " mB buffered, network energy: "
                                + assembly.getNetworkEnergy() + " RF"));
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

    @Override
    @Nullable
    public TileEntity createTileEntity(@Nonnull World world, @Nonnull IBlockState state) {
        return new TileMEOutputAssembly();
    }

    @Override
    @Nullable
    public TileEntity createNewTileEntity(@Nonnull World worldIn, int meta) {
        return new TileMEOutputAssembly();
    }

    @Override
    public void getDrops(@Nonnull NonNullList<ItemStack> drops, @Nonnull IBlockAccess world,
                         @Nonnull BlockPos pos, @Nonnull IBlockState state, int fortune) {
        TileEntity te = world.getTileEntity(pos);
        ItemStack dropped = new ItemStack(this);
        if (te instanceof TileMEOutputAssembly) {
            NBTTagCompound tag = new NBTTagCompound();
            ((TileMEOutputAssembly) te).writeBuffersToNBT(tag);
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
        if (te instanceof TileMEOutputAssembly && stack.hasTagCompound()) {
            ((TileMEOutputAssembly) te).readBuffersFromNBT(stack.getTagCompound());
        }
    }
}

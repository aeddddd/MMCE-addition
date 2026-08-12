package com.github.aeddddd.mmceaddition.block;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import com.github.aeddddd.mmceaddition.gui.GuiHandler;
import hellfirepvp.modularmachinery.common.block.BlockMachineComponent;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * ME 样板总成方块。
 */
public class BlockMEPatternAssembly extends BlockMachineComponent {

    public BlockMEPatternAssembly() {
        super(Material.IRON);
        setTranslationKey(MMCEAddition.MODID + ".me_pattern_assembly");
        setRegistryName(MMCEAddition.MODID, "me_pattern_assembly");
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
        setHardness(4.0f);
        setResistance(20.0f);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileMEPatternAssembly) {
                player.openGui(MMCEAddition.instance, GuiHandler.GUI_ME_PATTERN_ASSEMBLY, world, pos.getX(), pos.getY(), pos.getZ());
            }
        }
        return true;
    }

    /**
     * 声明该方块是完整不透明立方体（影响光照遮挡与相邻面剔除）。
     */
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return true;
    }

    /**
     * 渲染类型必须为 MODEL。
     * <p>
     * BlockContainer 默认返回 INVISIBLE（供 TESR 方块使用），
     * 不覆盖的话方块放置后会完全透明——这正是之前"放下后看不见"的原因。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    /**
     * MMCE 的 overlay 纹理带透明像素，需要在 CUTOUT 层渲染。
     */
    @Override
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileMEPatternAssembly();
    }

    @Override
    public net.minecraft.tileentity.TileEntity createNewTileEntity(World world, int meta) {
        return new TileMEPatternAssembly();
    }

    @Override
    public void dropBlockAsItemWithChance(World world, BlockPos pos, IBlockState state, float chance, int fortune) {
        // 破坏时保存 NBT 到掉落物
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileMEPatternAssembly) {
            TileMEPatternAssembly assembly = (TileMEPatternAssembly) tile;
            ItemStack drop = new ItemStack(this);
            NBTTagCompound tag = new NBTTagCompound();
            assembly.writeCustomNBT(tag);
            if (!tag.isEmpty()) {
                drop.setTagCompound(tag);
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
        if (tile instanceof TileMEPatternAssembly && stack.hasTagCompound()) {
            ((TileMEPatternAssembly) tile).readCustomNBT(stack.getTagCompound());
        }
    }
}

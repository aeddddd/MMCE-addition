package com.github.aeddddd.mmceaddition.block;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
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
 * ME 异步物品输出总线方块。
 * <p>
 * 继承 {@link BlockMachineComponent} 是为了获得 MMCE 机器组件的基础能力：
 * <ul>
 *   <li>作为机器结构的一部分被识别</li>
 *   <li>自动关联 TileEntity</li>
 *   <li>支持染色（ColorableMachineComponent）</li>
 *   <li>被 Mixin 的结构匹配逻辑识别</li>
 * </ul>
 */
public class BlockMEAsyncItemOutputBus extends BlockMachineComponent {

    /**
     * 构造方块并设置基本属性。
     * <p>
     * setTranslationKey：设置未本地化的名称键，语言文件里用 tile.modid.name 对应。
     * setRegistryName：设置注册名，也是资源路径名。
     * setHardness/Resistance/HarvestLevel：挖掘相关属性。
     * setCreativeTab：放到本模组的创造栏。
     */
    public BlockMEAsyncItemOutputBus() {
        super(Material.IRON);
        setTranslationKey(MMCEAddition.MODID + ".me_async_item_output_bus");
        setRegistryName(MMCEAddition.MODID, "me_async_item_output_bus");
        setHardness(2.0f);
        setResistance(10.0f);
        setHarvestLevel("pickaxe", 1);
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
    }

    /**
     * 玩家右键点击方块时触发。
     * <p>
     * 这里仅在服务端打印当前缓冲区内的物品数量，用于调试。
     * 后续如果要加 GUI，可以在这里打开 GUI。
     *
     * @return true 表示已处理交互，不会继续触发其他行为
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            TileEntity te = worldIn.getTileEntity(pos);
            if (te instanceof TileMEAsyncItemOutputBus) {
                TileMEAsyncItemOutputBus bus = (TileMEAsyncItemOutputBus) te;
                playerIn.sendMessage(new TextComponentString(
                        "ME Async Item Output Bus: " + bus.getBufferedAmount() + " items buffered"));
            }
        }
        return true;
    }

    /**
     * 声明该方块是完整不透明立方体。
     * <p>
     * 这会影响：光照遮挡、相邻面剔除、怪物生成判断等。
     */
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    /**
     * 声明该方块占据完整 1x1x1 空间。
     * <p>
     * 这会影响：碰撞箱、选中框、实体移动判断等。
     */
    @Override
    public boolean isFullCube(IBlockState state) {
        return true;
    }

    /**
     * 设置方块渲染类型为 MODEL。
     * <p>
     * BlockMachineComponent 继承自 BlockContainer，默认返回 ENTITYBLOCK_ANIMATED，
     * 这会导致方块尝试用 TileEntitySpecialRenderer 渲染，没有注册 TESR 时就是透明的。
     * 覆盖为 MODEL 后使用普通的 blockstate/model 渲染。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    /**
     * 设置方块渲染层为 CUTOUT。
     * <p>
     * MMCE 的 overlay 纹理带有透明像素，必须在 CUTOUT 或 TRANSLUCENT 层渲染才能正确丢弃透明像素。
     * CUTOUT 适合 1-bit 透明（完全透明或完全不透明）的纹理。
     */
    @Override
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    /**
     * 创建与方块关联的 TileEntity。
     * <p>
     * 当世界需要这个方块的 TileEntity 时，Forge 会调用此方法。
     *
     * @return 新的 TileMEAsyncItemOutputBus 实例
     */
    @Override
    @Nullable
    public TileEntity createTileEntity(@Nonnull World world, @Nonnull IBlockState state) {
        return new TileMEAsyncItemOutputBus();
    }

    /**
     * 旧版接口，某些 Forge/原版代码路径仍会调用。
     * <p>
     * 在 1.12.2 中通常与 createTileEntity 返回相同结果。
     */
    @Override
    @Nullable
    public TileEntity createNewTileEntity(@Nonnull World worldIn, int meta) {
        return new TileMEAsyncItemOutputBus();
    }

    /**
     * 方块被破坏时生成掉落物。
     * <p>
     * 我们重写此方法，把 TileEntity 缓冲区中的物品 NBT 写入掉落物，
     * 这样玩家拆掉方块再放置时不会丢失缓冲内容。
     *
     * @param drops 掉落物列表
     */
    @Override
    public void getDrops(@Nonnull NonNullList<ItemStack> drops, @Nonnull IBlockAccess world,
                         @Nonnull BlockPos pos, @Nonnull IBlockState state, int fortune) {
        TileEntity te = world.getTileEntity(pos);
        ItemStack dropped = new ItemStack(this);
        if (te instanceof TileMEAsyncItemOutputBus) {
            TileMEAsyncItemOutputBus bus = (TileMEAsyncItemOutputBus) te;
            NBTTagCompound tag = new NBTTagCompound();
            bus.writeBufferToNBT(tag);
            if (!tag.isEmpty()) {
                dropped.setTagCompound(tag);
            }
        }
        drops.add(dropped);
    }

    /**
     * 方块被放置后恢复 TileEntity 数据。
     * <p>
     * 如果玩家手持的 ItemStack 带有之前保存的 NBT，就读取并恢复到新放置的 TileEntity。
     */
    @Override
    public void onBlockPlacedBy(World worldIn, BlockPos pos, IBlockState state,
                                net.minecraft.entity.EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(worldIn, pos, state, placer, stack);
        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TileMEAsyncItemOutputBus && stack.hasTagCompound()) {
            ((TileMEAsyncItemOutputBus) te).readBufferFromNBT(stack.getTagCompound());
        }
    }
}

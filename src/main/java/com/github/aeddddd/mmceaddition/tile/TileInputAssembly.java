package com.github.aeddddd.mmceaddition.tile;

import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import com.github.aeddddd.mmceaddition.util.LongInputFluidHandler;
import com.github.aeddddd.mmceaddition.util.LongInputItemHandler;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTile;
import hellfirepvp.modularmachinery.common.tiles.base.TileColorableMachineComponent;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 输入总成仓 TileEntity。
 * <p>
 * 超大容量（Long 上限）的物品/流体输入缓冲方块，<b>不与 AE 网络相连</b>，
 * 由外部自动化（漏斗、管道、其他模组的物流）经 Capability 填入，
 * 或玩家右键手持物品直接存入。
 * <p>
 * 通过 {@code RecipeCraftingContextMixin} 把 {@link #provideComponent()} 返回的
 * 标记组件展开为物品输入、流体输入两个独立 ProcessingComponent，
 * 绕过 MMCE 单 Tile 单组件的限制。不提供能源组件。
 */
public class TileInputAssembly extends TileColorableMachineComponent implements MachineComponentTile {

    /**
     * 物品/流体输入缓冲（Long 上限），机器经输入 handler 实时消耗。
     */
    private final LongItemBuffer itemBuffer = new LongItemBuffer();
    private final LongFluidBuffer fluidBuffer = new LongFluidBuffer();

    private final LongInputItemHandler itemHandler = new LongInputItemHandler(itemBuffer);
    private final LongInputFluidHandler fluidHandler = new LongInputFluidHandler(fluidBuffer);

    /**
     * 向 MMCE 机器提供组件能力。
     * <p>
     * 返回一个可被 Mixin 识别的标记组件（物品输入总线）。在组件装配阶段，
     * Mixin 会把它展开为物品输入、流体输入两个独立组件。
     */
    @Nullable
    @Override
    public MachineComponent<?> provideComponent() {
        return new InputAssemblyItemInputBus(this);
    }

    public ProcessingComponent<IItemHandlerModifiable> createItemInputProcessingComponent() {
        return new ProcessingComponent<>(new InputAssemblyItemInputBus(this), itemHandler, null);
    }

    public ProcessingComponent<net.minecraftforge.fluids.capability.IFluidHandler> createFluidInputProcessingComponent() {
        return new ProcessingComponent<>(new InputAssemblyFluidInputHatch(this), fluidHandler, null);
    }

    public LongItemBuffer getItemBuffer() {
        return itemBuffer;
    }

    public LongFluidBuffer getFluidBuffer() {
        return fluidBuffer;
    }

    public long getBufferedItemAmount() {
        return itemBuffer.getTotalAmount();
    }

    public long getBufferedFluidAmount() {
        return fluidBuffer.getTotalAmount();
    }

    /**
     * 直接向物品缓冲存入一组物品（玩家右键交互用）。
     * <p>
     * 绕过 handler 直写缓冲后必须重建槽位→变体映射，否则机器看不到新存入的物品。
     *
     * @return 放不下的剩余部分
     */
    @Nonnull
    public net.minecraft.item.ItemStack insertItemToBuffer(@Nonnull net.minecraft.item.ItemStack stack) {
        net.minecraft.item.ItemStack remainder = itemBuffer.insert(stack);
        itemHandler.syncWithBuffer();
        return remainder;
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                || capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
                || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(itemHandler);
        }
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(fluidHandler);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public void readCustomNBT(NBTTagCompound compound) {
        super.readCustomNBT(compound);
        if (compound.hasKey("ItemBuffer")) {
            itemBuffer.readFromNBT(compound.getCompoundTag("ItemBuffer"));
        }
        if (compound.hasKey("FluidBuffer")) {
            fluidBuffer.readFromNBT(compound.getCompoundTag("FluidBuffer"));
        }
        // 缓冲区直接重建后，输入 handler 的槽位→变体映射需要同步，否则对外显示为空
        itemHandler.syncWithBuffer();
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        NBTTagCompound itemTag = new NBTTagCompound();
        itemBuffer.writeToNBT(itemTag);
        compound.setTag("ItemBuffer", itemTag);
        NBTTagCompound fluidTag = new NBTTagCompound();
        fluidBuffer.writeToNBT(fluidTag);
        compound.setTag("FluidBuffer", fluidTag);
    }

    /**
     * 把缓冲区内容写入独立的 NBT（用于方块掉落）。
     */
    public void writeBuffersToNBT(NBTTagCompound compound) {
        NBTTagCompound itemTag = new NBTTagCompound();
        itemBuffer.writeToNBT(itemTag);
        compound.setTag("ItemBuffer", itemTag);
        NBTTagCompound fluidTag = new NBTTagCompound();
        fluidBuffer.writeToNBT(fluidTag);
        compound.setTag("FluidBuffer", fluidTag);
    }

    /**
     * 从独立 NBT 恢复缓冲区内容（用于方块放置）。
     */
    public void readBuffersFromNBT(NBTTagCompound compound) {
        if (compound.hasKey("ItemBuffer")) {
            itemBuffer.readFromNBT(compound.getCompoundTag("ItemBuffer"));
        }
        if (compound.hasKey("FluidBuffer")) {
            fluidBuffer.readFromNBT(compound.getCompoundTag("FluidBuffer"));
        }
        itemHandler.syncWithBuffer();
    }

    /**
     * 可被 Mixin 识别的标记组件（物品输入总线）。
     */
    public static class InputAssemblyItemInputBus extends MachineComponent.ItemBus {
        private final TileInputAssembly assembly;

        public InputAssemblyItemInputBus(TileInputAssembly assembly) {
            super(IOType.INPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IItemHandlerModifiable getContainerProvider() {
            return assembly.itemHandler;
        }

        public TileInputAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 流体输入仓组件。
     */
    public static class InputAssemblyFluidInputHatch extends MachineComponent.FluidHatch {
        private final TileInputAssembly assembly;

        public InputAssemblyFluidInputHatch(TileInputAssembly assembly) {
            super(IOType.INPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public LongInputFluidHandler getContainerProvider() {
            return assembly.fluidHandler;
        }

        public TileInputAssembly getAssembly() {
            return assembly;
        }
    }
}

package com.github.aeddddd.mmceaddition.tile;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEPartLocation;
import appeng.fluids.helper.IFluidInterfaceHost;
import appeng.fluids.util.AEFluidStack;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.me.helpers.MachineSource;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.LinkedHashMap;
import java.util.Map;

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
public class TileInputAssembly extends TileColorableMachineComponent
        implements MachineComponentTile, com.github.aeddddd.mmceaddition.gui.IBufferViewProvider {

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

    @Override
    public LongItemBuffer getViewItemBuffer() {
        return itemBuffer;
    }

    @Override
    public LongFluidBuffer getViewFluidBuffer() {
        return fluidBuffer;
    }

    /**
     * GUI 直写缓冲后重建槽位→变体映射，否则机器看不到新存入的物品。
     */
    @Override
    public void onViewBufferModified() {
        itemHandler.syncWithBuffer();
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

    /**
     * AE2 物品/流体存储通道（类加载时获取，与 MEAsyncOutputManager 一致）。
     */
    private static final IItemStorageChannel ITEM_CHANNEL =
            AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    private static final IFluidStorageChannel FLUID_CHANNEL =
            AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

    /**
     * 把物品/流体缓存返还给相邻的 ME 接口所在网络。
     * <p>
     * 识别六个相邻面上的接口宿主：AE2 的 ME 接口（{@link IInterfaceHost}，含
     * AE2FC 二合一/三合一接口，它们同样实现该接口）与流体接口
     * （{@link IFluidInterfaceHost}），方块与线缆面板（{@link IPartHost}）形态均可。
     * 多个接口属于同一网格时只注入一次；注入以相邻接口的身份
     * （{@link MachineSource}）执行，消耗其网络能量。
     *
     * @return {@code long[]{已返还物品数, 已返还流体 mB 数}}；相邻面没有任何可用接口时返回 null
     */
    @Nullable
    public long[] returnBuffersToAdjacentInterfaces() {
        if (world == null || world.isRemote) {
            return null;
        }
        // 网格 → 注入身份（按发现顺序去重）
        Map<IGrid, IActionHost> grids = new LinkedHashMap<>();
        for (EnumFacing facing : EnumFacing.VALUES) {
            TileEntity neighbor = world.getTileEntity(pos.offset(facing));
            if (neighbor == null) {
                continue;
            }
            collectInterfaceGrid(neighbor, grids);
            if (neighbor instanceof IPartHost) {
                for (AEPartLocation location : AEPartLocation.values()) {
                    IPart part = ((IPartHost) neighbor).getPart(location);
                    if (part != null) {
                        collectInterfaceGrid(part, grids);
                    }
                }
            }
        }
        if (grids.isEmpty()) {
            return null;
        }

        long itemsReturned = 0;
        long fluidsReturned = 0;
        for (Map.Entry<IGrid, IActionHost> entry : grids.entrySet()) {
            IStorageGrid storage = entry.getKey().getCache(IStorageGrid.class);
            IEnergySource energy = entry.getKey().getCache(IEnergyGrid.class);
            if (storage == null || energy == null) {
                continue;
            }
            IActionSource source = new MachineSource(entry.getValue());
            if (!itemBuffer.isEmpty()) {
                itemsReturned += injectItems(storage.getInventory(ITEM_CHANNEL), energy, source);
            }
            if (!fluidBuffer.isEmpty()) {
                fluidsReturned += injectFluids(storage.getInventory(FLUID_CHANNEL), energy, source);
            }
            if (itemBuffer.isEmpty() && fluidBuffer.isEmpty()) {
                break;
            }
        }
        if (itemsReturned > 0 || fluidsReturned > 0) {
            // 缓冲被直写后重建槽位→变体映射并标脏保存
            onViewBufferModified();
            markDirty();
        }
        return new long[]{itemsReturned, fluidsReturned};
    }

    /**
     * 若宿主是 ME 接口/流体接口则解析其所在网格并登记。
     */
    private static void collectInterfaceGrid(Object host, Map<IGrid, IActionHost> grids) {
        if (!(host instanceof IInterfaceHost) && !(host instanceof IFluidInterfaceHost)) {
            return;
        }
        if (!(host instanceof IActionHost)) {
            return;
        }
        IGrid grid = null;
        try {
            if (host instanceof IGridProxyable) {
                grid = ((IGridProxyable) host).getProxy().getGrid();
            } else {
                IGridNode node = ((IActionHost) host).getActionableNode();
                if (node != null) {
                    grid = node.getGrid();
                }
            }
        } catch (GridAccessException ignored) {
            // 接口尚未连入网格（未通电/未连线），跳过
        }
        if (grid != null && !grids.containsKey(grid)) {
            grids.put(grid, (IActionHost) host);
        }
    }

    /**
     * 把物品缓冲注入指定网格，返回实际注入数量。逻辑与 MEAsyncOutputManager 一致。
     */
    private long injectItems(IMEMonitor<IAEItemStack> monitor, IEnergySource energy, IActionSource source) {
        long total = 0;
        for (Map.Entry<ItemVariant, Long> entry : itemBuffer.snapshot().entrySet()) {
            ItemVariant variant = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            IAEItemStack toInsert = AEItemStack.fromItemStack(variant.toSingleStack());
            if (toInsert == null) {
                itemBuffer.extract(variant, amount);
                continue;
            }
            toInsert.setStackSize(amount);
            IAEItemStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                itemBuffer.extract(variant, inserted);
                total += inserted;
            }
        }
        return total;
    }

    /**
     * 把流体缓冲注入指定网格，返回实际注入 mB 数。逻辑与 MEAsyncOutputManager 一致。
     */
    private long injectFluids(IMEMonitor<IAEFluidStack> monitor, IEnergySource energy, IActionSource source) {
        long total = 0;
        for (Map.Entry<Fluid, Long> entry : fluidBuffer.snapshot().entrySet()) {
            Fluid fluid = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            IAEFluidStack toInsert = AEFluidStack.fromFluidStack(new FluidStack(fluid, 1));
            if (toInsert == null) {
                fluidBuffer.extract(fluid, amount);
                continue;
            }
            toInsert.setStackSize(amount);
            IAEFluidStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                fluidBuffer.extract(fluid, inserted);
                total += inserted;
            }
        }
        return total;
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

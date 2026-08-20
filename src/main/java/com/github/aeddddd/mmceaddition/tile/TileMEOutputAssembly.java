package com.github.aeddddd.mmceaddition.tile;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.me.helpers.MachineSource;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.compat.NetworkEnergyCompat;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import com.github.aeddddd.mmceaddition.util.IBufferObserver;
import com.github.aeddddd.mmceaddition.util.LongBufferFluidHandler;
import com.github.aeddddd.mmceaddition.util.LongBufferItemHandler;
import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTile;
import hellfirepvp.modularmachinery.common.tiles.base.TileColorableMachineComponent;
import hellfirepvp.modularmachinery.common.util.IEnergyHandlerAsync;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ME 输出总成仓 TileEntity。
 * <p>
 * 单个方块同时充当机器的物品输出总线、流体输出仓与能源仓：
 * <ul>
 *   <li>物品/流体产出先进入 {@link LongItemBuffer} / {@link LongFluidBuffer} 缓冲，
 *       由 {@link MEAsyncOutputManager} 按网格批量回注 AE（与异步总线同一管线）</li>
 *   <li>能源输入：安装 AE2Enhanced 时，机器能耗直接从 AE 网络存储的 RF 中扣除
 *       （{@link NetworkEnergyCompat#extractEnergy}，全有或全无）</li>
 *   <li>能源输出：ae2e 提供 insertEnergy API 时，机器产电直接回注 AE 网络；
 *       旧版 ae2e 无此方法时能源输出失效（receiveEnergy 恒 false），其余功能不受影响</li>
 * </ul>
 * 通过 {@code RecipeCraftingContextMixin} 把 {@link #provideComponent()} 返回的
 * 标记组件展开为物品输出、流体输出、能源输入、能源输出四个独立 ProcessingComponent，
 * 绕过 MMCE 单 Tile 单组件的限制。
 */
public class TileMEOutputAssembly extends TileColorableMachineComponent
        implements MachineComponentTile, IActionHost, IGridProxyable, IBufferObserver {

    /**
     * 物品/流体输出缓冲（Long 上限），产出由管理器批量注入 AE。
     */
    private final LongItemBuffer itemBuffer = new LongItemBuffer(this);
    private final LongFluidBuffer fluidBuffer = new LongFluidBuffer(this);

    private final LongBufferItemHandler itemHandler = new LongBufferItemHandler(itemBuffer);
    private final LongBufferFluidHandler fluidHandler = new LongBufferFluidHandler(fluidBuffer);

    /**
     * 网络 RF 能源处理器：把"当前能量/提取/接收"直接映射为 AE 网络 RF 存量的查询、扣除与回注。
     */
    private final NetworkEnergyHandler energyHandler = new NetworkEnergyHandler();

    private final AENetworkProxy proxy;
    private final IActionSource source;

    private boolean registered = false;

    public TileMEOutputAssembly() {
        this.proxy = new AENetworkProxy(this, "aeProxy", new ItemStack(RegistryHandler.ME_OUTPUT_ASSEMBLY), true);
        this.proxy.setIdlePowerUsage(1.0);
        this.proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        this.source = new MachineSource(this);
    }

    /**
     * 向 MMCE 机器提供组件能力。
     * <p>
     * 返回一个可被 Mixin 识别的标记组件（物品输出总线）。在
     * RecipeCraftingContext.updateComponents 阶段，Mixin 会把它展开为
     * 物品输出、流体输出、能源输入、能源输出四个独立组件。
     */
    @Nullable
    @Override
    public MachineComponent<?> provideComponent() {
        return new OutputAssemblyItemOutputBus(this);
    }

    public ProcessingComponent<IItemHandlerModifiable> createItemOutputProcessingComponent() {
        return new ProcessingComponent<>(new OutputAssemblyItemOutputBus(this), itemHandler, null);
    }

    public ProcessingComponent<IFluidHandler> createFluidOutputProcessingComponent() {
        return new ProcessingComponent<>(new OutputAssemblyFluidOutputHatch(this), fluidHandler, null);
    }

    /**
     * 能源输入组件：机器能耗直接消耗 AE 网络存储的 RF（需 AE2Enhanced）。
     */
    public ProcessingComponent<IEnergyHandlerAsync> createEnergyInputProcessingComponent() {
        return new ProcessingComponent<>(new OutputAssemblyEnergyInputHatch(this), energyHandler, null);
    }

    /**
     * 能源输出组件：机器产电直接回注 AE 网络（需 ae2e insertEnergy API）。
     */
    public ProcessingComponent<IEnergyHandlerAsync> createEnergyOutputProcessingComponent() {
        return new ProcessingComponent<>(new OutputAssemblyEnergyOutputHatch(this), energyHandler, null);
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
     * @return 是否有待注入 AE 的物品/流体产出
     */
    public boolean hasAnyOutput() {
        return !itemBuffer.isEmpty() || !fluidBuffer.isEmpty();
    }

    /**
     * @return 当前网络中存储的 RF 总量（供右键诊断显示）
     */
    public long getNetworkEnergy() {
        return NetworkEnergyCompat.getStoredEnergy(this);
    }

    @Nonnull
    @Override
    public AENetworkProxy getProxy() {
        return proxy;
    }

    @Nullable
    @Override
    public IGridNode getGridNode(@Nonnull AEPartLocation dir) {
        return proxy.getNode();
    }

    @Nonnull
    @Override
    public AECableType getCableConnectionType(@Nonnull AEPartLocation dir) {
        return AECableType.SMART;
    }

    @Nonnull
    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public void gridChanged() {
    }

    @Nonnull
    @Override
    public IGridNode getActionableNode() {
        return proxy.getNode();
    }

    @Override
    public void securityBreak() {
        if (world != null && pos != null) {
            world.setBlockToAir(pos);
        }
    }

    @Override
    public void validate() {
        super.validate();
        proxy.validate();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!world.isRemote) {
            proxy.onReady();
            if (!registered) {
                MEAsyncOutputManager.INSTANCE.register(this);
                registered = true;
            }
            // 重进存档时，缓冲区可能已从 NBT 恢复出非空内容，
            // 但不会触发 insert/fill 时的 onBufferNonEmpty 回调，这里显式补一次 dirty 标记。
            if (hasAnyOutput()) {
                MEAsyncOutputManager.INSTANCE.markDirty(this);
            }
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        proxy.invalidate();
        unregisterManager();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        proxy.onChunkUnload();
        unregisterManager();
    }

    private void unregisterManager() {
        if (registered) {
            MEAsyncOutputManager.INSTANCE.unregister(this);
            registered = false;
        }
    }

    public IActionSource getSource() {
        return source;
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
        try {
            proxy.readFromNBT(compound);
        } catch (IllegalStateException e) {
            // 忽略非法状态异常，通常发生在代理尚未初始化时
        }
        if (compound.hasKey("ItemBuffer")) {
            itemBuffer.readFromNBT(compound.getCompoundTag("ItemBuffer"));
        }
        if (compound.hasKey("FluidBuffer")) {
            fluidBuffer.readFromNBT(compound.getCompoundTag("FluidBuffer"));
        }
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        proxy.writeToNBT(compound);
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
    }

    /**
     * 缓冲区观察者回调：缓冲区从空变非空时把本 Tile 标记为待处理。
     */
    @Override
    public void onBufferNonEmpty() {
        if (world != null && !world.isRemote) {
            MEAsyncOutputManager.INSTANCE.markDirty(this);
        }
    }

    /**
     * 可被 Mixin 识别的标记组件（物品输出总线）。
     */
    public static class OutputAssemblyItemOutputBus extends MachineComponent.ItemBus {
        private final TileMEOutputAssembly assembly;

        public OutputAssemblyItemOutputBus(TileMEOutputAssembly assembly) {
            super(IOType.OUTPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IItemHandlerModifiable getContainerProvider() {
            return assembly.itemHandler;
        }

        public TileMEOutputAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 流体输出仓组件。
     */
    public static class OutputAssemblyFluidOutputHatch extends MachineComponent.FluidHatch {
        private final TileMEOutputAssembly assembly;

        public OutputAssemblyFluidOutputHatch(TileMEOutputAssembly assembly) {
            super(IOType.OUTPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IFluidHandler getContainerProvider() {
            return assembly.fluidHandler;
        }

        public TileMEOutputAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 能源输入仓组件：让 RequirementEnergy(INPUT) 把本仓识别为能源输入口。
     */
    public static class OutputAssemblyEnergyInputHatch extends MachineComponent.EnergyHatch {
        private final TileMEOutputAssembly assembly;

        public OutputAssemblyEnergyInputHatch(TileMEOutputAssembly assembly) {
            super(IOType.INPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IEnergyHandlerAsync getContainerProvider() {
            return assembly.energyHandler;
        }

        public TileMEOutputAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 能源输出仓组件：让 RequirementEnergy(OUTPUT) 把本仓识别为能源输出口。
     */
    public static class OutputAssemblyEnergyOutputHatch extends MachineComponent.EnergyHatch {
        private final TileMEOutputAssembly assembly;

        public OutputAssemblyEnergyOutputHatch(TileMEOutputAssembly assembly) {
            super(IOType.OUTPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IEnergyHandlerAsync getContainerProvider() {
            return assembly.energyHandler;
        }

        public TileMEOutputAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 网络 RF 能源处理器（双向）。
     * <ul>
     *   <li>{@link #getCurrentEnergy()}：网络 RF 存量</li>
     *   <li>{@link #extractEnergy(long)}：全有或全无——先模拟确认存量充足再实际扣除，
     *       避免 RequirementEnergy 把部分提取误判为成功；未安装 ae2e 时表现为 0 能量</li>
     *   <li>{@link #receiveEnergy(long)}：全有或全无回注网络——ae2e 无 insertEnergy API
     *       或网络无法接收时返回 false，机器报能源输出失败而不会静默吞电</li>
     *   <li>{@link #setCurrentEnergy(long)}：空实现——网络能量是共享存储，本地写回没有意义</li>
     *   <li>{@link #getMaxEnergy()}：Long 上限，使能源输出的剩余容量检查恒通过</li>
     * </ul>
     */
    private class NetworkEnergyHandler implements IEnergyHandlerAsync {

        @Override
        public long getCurrentEnergy() {
            return NetworkEnergyCompat.getStoredEnergy(TileMEOutputAssembly.this);
        }

        @Override
        public void setCurrentEnergy(long l) {
            // 异步路径不会调用；网络能量是共享存储，本地写回没有意义
        }

        @Override
        public long getMaxEnergy() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean extractEnergy(long amount) {
            if (amount <= 0) {
                return true;
            }
            // 全有或全无：先模拟，存量不足则一点不动
            if (NetworkEnergyCompat.extractEnergy(TileMEOutputAssembly.this, amount, true) < amount) {
                return false;
            }
            return NetworkEnergyCompat.extractEnergy(TileMEOutputAssembly.this, amount, false) >= amount;
        }

        @Override
        public boolean receiveEnergy(long amount) {
            if (amount <= 0) {
                return true;
            }
            // 全有或全无：先模拟确认网络可接收再实际回注
            if (NetworkEnergyCompat.insertEnergy(TileMEOutputAssembly.this, amount, true) < amount) {
                return false;
            }
            return NetworkEnergyCompat.insertEnergy(TileMEOutputAssembly.this, amount, false) >= amount;
        }
    }
}

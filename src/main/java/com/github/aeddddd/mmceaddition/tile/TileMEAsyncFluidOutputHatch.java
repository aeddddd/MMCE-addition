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
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import com.github.aeddddd.mmceaddition.util.IBufferObserver;
import com.github.aeddddd.mmceaddition.util.LongBufferFluidHandler;
import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTile;
import hellfirepvp.modularmachinery.common.tiles.base.TileColorableMachineComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ME 异步流体输出仓 TileEntity。
 */
public class TileMEAsyncFluidOutputHatch extends TileColorableMachineComponent
        implements MachineComponentTile, IActionHost, IGridProxyable, IBufferObserver {

    private final LongFluidBuffer fluidBuffer = new LongFluidBuffer(this);
    private final LongBufferFluidHandler fluidHandler = new LongBufferFluidHandler(fluidBuffer);
    private final AENetworkProxy proxy;
    private final IActionSource source;

    private boolean registered = false;

    public TileMEAsyncFluidOutputHatch() {
        this.proxy = new AENetworkProxy(this, "aeProxy", new ItemStack(RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH), true);
        this.proxy.setIdlePowerUsage(1.0);
        this.proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        this.source = new MachineSource(this);
    }

    @Nullable
    @Override
    public MachineComponent.FluidHatch provideComponent() {
        return new MachineComponent.FluidHatch(IOType.OUTPUT) {
            @Nonnull
            @Override
            public IFluidHandler getContainerProvider() {
                return fluidHandler;
            }
        };
    }

    public LongFluidBuffer getFluidBuffer() {
        return fluidBuffer;
    }

    public long getBufferedAmount() {
        return fluidBuffer.getTotalAmount();
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
    public void onBufferNonEmpty() {
        if (world != null && !world.isRemote) {
            MEAsyncOutputManager.INSTANCE.markDirty(this);
        }
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
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
        if (compound.hasKey("Buffer")) {
            fluidBuffer.readFromNBT(compound.getCompoundTag("Buffer"));
        }
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        proxy.writeToNBT(compound);
        NBTTagCompound bufferTag = new NBTTagCompound();
        fluidBuffer.writeToNBT(bufferTag);
        compound.setTag("Buffer", bufferTag);
    }

    public void writeBufferToNBT(NBTTagCompound compound) {
        fluidBuffer.writeToNBT(compound);
    }

    public void readBufferFromNBT(NBTTagCompound compound) {
        fluidBuffer.readFromNBT(compound);
    }
}

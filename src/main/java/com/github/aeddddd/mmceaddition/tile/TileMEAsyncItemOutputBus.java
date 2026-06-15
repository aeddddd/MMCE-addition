package com.github.aeddddd.mmceaddition.tile;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.me.helpers.MachineSource;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import com.github.aeddddd.mmceaddition.util.IBufferObserver;
import com.github.aeddddd.mmceaddition.util.LongBufferItemHandler;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTile;
import hellfirepvp.modularmachinery.common.tiles.base.TileColorableMachineComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ME 异步物品输出总线 TileEntity。
 */
public class TileMEAsyncItemOutputBus extends TileColorableMachineComponent
        implements MachineComponentTile, IActionHost, IGridProxyable, IBufferObserver {

    private final LongItemBuffer itemBuffer = new LongItemBuffer(this);
    private final LongBufferItemHandler itemHandler = new LongBufferItemHandler(itemBuffer);
    private final AENetworkProxy proxy;
    private final IActionSource source;

    private boolean registered = false;

    public TileMEAsyncItemOutputBus() {
        this.proxy = new AENetworkProxy(this, "aeProxy", new ItemStack(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS), true);
        this.proxy.setIdlePowerUsage(1.0);
        this.proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        this.source = new MachineSource(this);
    }

    @Nullable
    @Override
    public MachineComponent.ItemBus provideComponent() {
        return new MachineComponent.ItemBus(IOType.OUTPUT) {
            @Nonnull
            @Override
            public IItemHandlerModifiable getContainerProvider() {
                return itemHandler;
            }
        };
    }

    public LongItemBuffer getItemBuffer() {
        return itemBuffer;
    }

    public long getBufferedAmount() {
        return itemBuffer.getTotalAmount();
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
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(itemHandler);
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
            itemBuffer.readFromNBT(compound.getCompoundTag("Buffer"));
        }
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        proxy.writeToNBT(compound);
        NBTTagCompound bufferTag = new NBTTagCompound();
        itemBuffer.writeToNBT(bufferTag);
        compound.setTag("Buffer", bufferTag);
    }

    public void writeBufferToNBT(NBTTagCompound compound) {
        itemBuffer.writeToNBT(compound);
    }

    public void readBufferFromNBT(NBTTagCompound compound) {
        itemBuffer.readFromNBT(compound);
    }
}

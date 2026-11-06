package com.github.aeddddd.mmceaddition.tile;

import ae2.api.AECapabilities;
import ae2.api.networking.GridFlags;
import ae2.api.networking.GridHelper;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.security.IActionSource;
import ae2.api.util.AECableType;
import ae2.me.helpers.IGridConnectedTile;
import hellfirepvp.modularmachinery.common.tiles.base.TileColorableMachineComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import javax.annotation.Nullable;

/** Shared native AE2S node lifecycle; no legacy proxy or per-device grid ticker. */
public abstract class TileMENetworkComponent extends TileColorableMachineComponent implements IGridConnectedTile, com.github.aeddddd.mmceaddition.util.IBufferObserver {
    private static final IGridNodeListener<TileMENetworkComponent> LISTENER = new IGridNodeListener<>() {
        @Override public void onSaveChanges(TileMENetworkComponent owner, IGridNode node) { owner.markDirty(); }
        @Override public void onStateChanged(TileMENetworkComponent owner, IGridNode node, State state) {
            owner.onMainNodeStateChanged(state);
        }
    };
    private IManagedGridNode mainNode = newNode();
    private boolean destroyed;
    private NBTTagCompound savedNode = new NBTTagCompound();
    protected final IActionSource source = IActionSource.ofMachine(this);

    private IManagedGridNode newNode() {
        return GridHelper.createManagedNode(this, LISTENER).setInWorldNode(true).setTagName("aeProxy")
                .setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(1.0);
    }
    public abstract ItemStack getVisualItemStack();
    protected void configureNode(IManagedGridNode node) { }
    @Override public IManagedGridNode getMainNode() { return mainNode; }
    public IActionSource getSource() { return source; }
    @Override public void onBufferChanged() { markDirty(); }
    @Override public void onBufferNonEmpty() { }
    @Override public void saveChanges() { markDirty(); }
    @Override public AECableType getCableConnectionType(EnumFacing side) { return AECableType.SMART; }
    @Override public void onLoad() {
        super.onLoad();
        if (world == null || world.isRemote) return;
        if (destroyed) {
            mainNode = newNode();
            mainNode.loadFromNBT(savedNode);
            destroyed = false;
        }
        mainNode.setVisualRepresentation(getVisualItemStack());
        configureNode(mainNode);
        GridHelper.onFirstTick(this, tile -> {
            if (!tile.isInvalid() && !tile.destroyed && !tile.mainNode.isReady()) {
                tile.mainNode.create(tile.world, tile.pos);
            }
        });
    }
    private void destroyNode() {
        if (!destroyed) {
            savedNode = new NBTTagCompound();
            mainNode.saveToNBT(savedNode);
            mainNode.destroy();
            destroyed = true;
        }
    }
    @Override public void invalidate() { destroyNode(); super.invalidate(); }
    @Override public void onChunkUnload() { destroyNode(); super.onChunkUnload(); }
    @Override public void readCustomNBT(NBTTagCompound data) {
        super.readCustomNBT(data);
        if (!mainNode.isReady()) mainNode.loadFromNBT(data);
    }
    @Override public void writeCustomNBT(NBTTagCompound data) {
        super.writeCustomNBT(data);
        if (destroyed) data.setTag("aeProxy", savedNode.getCompoundTag("aeProxy").copy());
        else mainNode.saveToNBT(data);
    }
    protected void notifyNeighbors() {
        if (world != null && !world.isRemote) {
            world.notifyNeighborsOfStateChange(pos, getBlockType(), false);
        }
    }
    @Override public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing side) {
        return capability == AECapabilities.IN_WORLD_GRID_NODE_HOST || super.hasCapability(capability, side);
    }
    @Override public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing side) {
        return capability == AECapabilities.IN_WORLD_GRID_NODE_HOST
                ? AECapabilities.IN_WORLD_GRID_NODE_HOST.cast(this) : super.getCapability(capability, side);
    }
}

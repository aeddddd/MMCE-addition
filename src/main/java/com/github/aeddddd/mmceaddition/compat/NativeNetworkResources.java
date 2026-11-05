package com.github.aeddddd.mmceaddition.compat;

import ae2.api.config.Actionable;
import ae2.api.networking.IGridNode;
import ae2.api.networking.security.IActionHost;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import ae2.api.storage.MEStorage;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/** Optional resource types are resolved through AE2S's registered key API. */
final class NativeNetworkResources {
    private NativeNetworkResources() { }
    static AEKey scalar(String name) {
        return key("ae2enhanced", name, new NBTTagCompound());
    }
    static AEKey key(String namespace, String name, NBTTagCompound data) {
        ResourceLocation id = new ResourceLocation(namespace, name);
        for (AEKeyType type : AEKeyTypes.getAll()) {
            if (type.getId().equals(id)) return type.loadKeyFromTag(data);
        }
        return null;
    }
    static long transfer(IActionHost host, AEKey key, long amount, boolean simulate, boolean insert) {
        if (host == null || key == null || amount <= 0) return 0;
        net.minecraft.server.MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.isServerStopped()) return 0;
        if (server.isCallingFromMinecraftThread()) return transferNow(host, key, amount, simulate, insert);
        // MMCE drains this queue while awaiting recipe workers. Minecraft's
        // ordinary scheduled-task queue would deadlock against that wait.
        java.util.concurrent.CompletableFuture<Long> result = new java.util.concurrent.CompletableFuture<>();
        hellfirepvp.modularmachinery.ModularMachinery.EXECUTE_MANAGER.addSyncTask(() -> {
            try { result.complete(transferNow(host, key, amount, simulate, insert)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.join();
    }
    private static long transferNow(IActionHost host, AEKey key, long amount, boolean simulate, boolean insert) {
        IGridNode node = host.getActionableNode();
        if (node == null || !node.isActive()) return 0;
        MEStorage storage = node.grid().getStorageService().getInventory();
        Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        IActionSource source = IActionSource.ofMachine(host);
        // Make the commit all-or-nothing on the owning thread. Separate worker
        // preflights cannot reserve shared resources against other recipe workers.
        if (!simulate) {
            long available = insert ? storage.insert(key, amount, Actionable.SIMULATE, source)
                    : storage.extract(key, amount, Actionable.SIMULATE, source);
            if (available < amount) return 0;
        }
        // Resource units are defined by the optional AE2S key type, rather than AE power.
        return insert ? storage.insert(key, amount, action, source) : storage.extract(key, amount, action, source);
    }
}

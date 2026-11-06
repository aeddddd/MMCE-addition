package com.github.aeddddd.mmceaddition.compat;

import ae2.api.networking.security.IActionHost;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyTypes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/** Optional Mana, Starlight and Essentia storage through the native AE2S key registry. */
public final class NetworkResourceCompat {
    private NetworkResourceCompat() { }
    public static boolean isManaAvailable() { return NativeNetworkResources.scalar("mana") != null; }
    public static long getStoredMana(IActionHost host) { return extractMana(host, Long.MAX_VALUE, true); }
    public static long extractMana(IActionHost host, long amount, boolean simulate) {
        return NativeNetworkResources.transfer(host, NativeNetworkResources.scalar("mana"), amount, simulate, false);
    }
    public static boolean isStarlightAvailable() { return NativeNetworkResources.scalar("starlight") != null; }
    public static long getStoredStarlight(IActionHost host) { return extractStarlight(host, Long.MAX_VALUE, true); }
    public static long extractStarlight(IActionHost host, long amount, boolean simulate) {
        return NativeNetworkResources.transfer(host, NativeNetworkResources.scalar("starlight"), amount, simulate, false);
    }
    public static boolean isEssentiaAvailable() {
        return AEKeyTypes.getAll().stream().anyMatch(type -> type.getId().equals(new ResourceLocation("thaumicenergistics", "essentia"))
                || type.getId().equals(new ResourceLocation("ae2enhanced", "essentia")));
    }
    public static int extractEssentia(IActionHost host, String aspect, int amount, boolean simulate) {
        if (aspect == null || amount <= 0) return 0;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Aspect", aspect);
        AEKey key = NativeNetworkResources.key("thaumicenergistics", "essentia", tag);
        if (key == null) {
            tag.setString("aspect", aspect);
            key = NativeNetworkResources.key("ae2enhanced", "essentia", tag);
        }
        return (int) NativeNetworkResources.transfer(host, key, amount, simulate, false);
    }
}

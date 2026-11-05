package com.github.aeddddd.mmceaddition.compat;

import ae2.api.networking.security.IActionHost;

/** Optional AE2Enhanced RF storage, addressed through its registered native AE2S key. */
public final class NetworkEnergyCompat {
    private NetworkEnergyCompat() { }
    public static boolean isAvailable() { return NativeNetworkResources.scalar("rf") != null; }
    public static boolean isInsertAvailable() { return isAvailable(); }
    public static long getStoredEnergy(IActionHost host) { return extractEnergy(host, Long.MAX_VALUE, true); }
    public static long extractEnergy(IActionHost host, long amount, boolean simulate) {
        return NativeNetworkResources.transfer(host, NativeNetworkResources.scalar("rf"), amount, simulate, false);
    }
    public static long insertEnergy(IActionHost host, long amount, boolean simulate) {
        return NativeNetworkResources.transfer(host, NativeNetworkResources.scalar("rf"), amount, simulate, true);
    }
}

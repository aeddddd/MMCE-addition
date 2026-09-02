package com.github.aeddddd.mmceaddition.compat;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.helpers.MachineSource;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 网络资源（Mana / Starlight / Essentia）API 的反射桥（软依赖）。
 * <p>
 * 与 {@link NetworkEnergyCompat} 同构，按资源分别探测：
 * <ul>
 *   <li>Mana：AE2Enhanced {@code NetworkManaApi}（需 ae2enhanced + Botania 通道在场）</li>
 *   <li>Starlight：AE2Enhanced {@code NetworkStarlightApi}（需 ae2enhanced + astralsorcery）</li>
 *   <li>Essentia：直接反射 ThaumicEnergistics 的源质存储通道
 *       （需 thaumcraft + thaumicenergistics，不经过 AE2Enhanced）</li>
 * </ul>
 * 所有网络操作在静态锁内执行：MMCE 的 Asyncable 需求检查/消耗可能运行在
 * RecipeThread 异步线程上，AE2 网络操作本身非线程安全。
 */
public final class NetworkResourceCompat {

    private static final String AE2E_MODID = "ae2enhanced";
    private static final String TE_MODID = "thaumicenergistics";

    private static final Object NETWORK_LOCK = new Object();

    // ==================== ae2e Mana / Starlight API（反射） ====================

    private static boolean ae2eInit = false;
    private static Method manaGetStored;
    private static Method manaExtract;
    private static Method starlightGetStored;
    private static Method starlightExtract;

    // ==================== ThaumicEnergistics Essentia（反射） ====================

    private static boolean teInit = false;
    private static IStorageChannel<?> essentiaChannel;
    private static Constructor<?> essentiaStackCtor;
    private static Method essentiaToAE;

    private NetworkResourceCompat() {
    }

    // ==================== Mana ====================

    public static boolean isManaAvailable() {
        ensureAe2eInit();
        return manaExtract != null;
    }

    public static long getStoredMana(IActionHost host) {
        return invokeLong(manaGetStored, host);
    }

    public static long extractMana(IActionHost host, long amount, boolean simulate) {
        if (!isManaAvailable() || host == null || amount <= 0) {
            return 0;
        }
        synchronized (NETWORK_LOCK) {
            return invokeLong(manaExtract, host, amount, simulate);
        }
    }

    // ==================== Starlight ====================

    public static boolean isStarlightAvailable() {
        ensureAe2eInit();
        return starlightExtract != null;
    }

    public static long getStoredStarlight(IActionHost host) {
        return invokeLong(starlightGetStored, host);
    }

    public static long extractStarlight(IActionHost host, long amount, boolean simulate) {
        if (!isStarlightAvailable() || host == null || amount <= 0) {
            return 0;
        }
        synchronized (NETWORK_LOCK) {
            return invokeLong(starlightExtract, host, amount, simulate);
        }
    }

    // ==================== Essentia ====================

    public static boolean isEssentiaAvailable() {
        ensureTeInit();
        return essentiaChannel != null;
    }

    /**
     * 从宿主所在网络的源质通道提取源质.
     *
     * @param aspectTag TC6 Aspect 的 tag（{@code Aspect.getTag()}）
     * @return 实际（或模拟可）提取的源质数量
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int extractEssentia(IActionHost host, String aspectTag, int amount, boolean simulate) {
        if (!isEssentiaAvailable() || host == null || aspectTag == null || amount <= 0) {
            return 0;
        }
        synchronized (NETWORK_LOCK) {
            try {
                Object essentiaStack = essentiaStackCtor.newInstance(aspectTag, amount);
                IAEStack request = (IAEStack) essentiaToAE.invoke(null, essentiaStack);
                if (request == null) {
                    return 0;
                }
                IGridNode node = host.getActionableNode();
                if (node == null) {
                    return 0;
                }
                IGrid grid = node.getGrid();
                IStorageGrid storage = grid.getCache(IStorageGrid.class);
                if (storage == null) {
                    return 0;
                }
                IMEInventory inventory = storage.getInventory((IStorageChannel) essentiaChannel);
                if (inventory == null) {
                    return 0;
                }
                IActionSource source = new MachineSource(host);
                IAEStack extracted = (IAEStack) inventory.extractItems(request,
                        simulate ? Actionable.SIMULATE : Actionable.MODULATE, source);
                return extracted == null ? 0 : (int) Math.min(extracted.getStackSize(), Integer.MAX_VALUE);
            } catch (Exception e) {
                return 0;
            }
        }
    }

    // ==================== 初始化 ====================

    private static void ensureAe2eInit() {
        if (!ae2eInit) {
            synchronized (NetworkResourceCompat.class) {
                if (!ae2eInit) {
                    initAe2e();
                    ae2eInit = true;
                }
            }
        }
    }

    private static void initAe2e() {
        if (!Loader.isModLoaded(AE2E_MODID)) {
            return;
        }
        try {
            Class<?> manaApi = Class.forName("com.github.aeddddd.ae2enhanced.api.NetworkManaApi");
            manaGetStored = manaApi.getMethod("getStoredMana", IActionHost.class);
            manaExtract = manaApi.getMethod("extractMana", IActionHost.class, long.class, boolean.class);
        } catch (Exception ignored) {
            // 旧版 ae2e 无 Mana API：Mana 网络访问不可用，其余资源不受影响
        }
        try {
            Class<?> starlightApi = Class.forName("com.github.aeddddd.ae2enhanced.api.NetworkStarlightApi");
            starlightGetStored = starlightApi.getMethod("getStoredStarlight", IActionHost.class);
            starlightExtract = starlightApi.getMethod("extractStarlight", IActionHost.class, long.class, boolean.class);
        } catch (Exception ignored) {
            // 同上：Starlight 网络访问不可用
        }
    }

    private static void ensureTeInit() {
        if (!teInit) {
            synchronized (NetworkResourceCompat.class) {
                if (!teInit) {
                    initTe();
                    teInit = true;
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void initTe() {
        if (!Loader.isModLoaded(TE_MODID)) {
            return;
        }
        try {
            Class<?> channelClass = Class.forName("thaumicenergistics.api.storage.IEssentiaStorageChannel");
            essentiaChannel = AEApi.instance().storage().getStorageChannel((Class) channelClass);
            Class<?> essentiaStackClass = Class.forName("thaumicenergistics.api.EssentiaStack");
            essentiaStackCtor = essentiaStackClass.getConstructor(String.class, int.class);
            essentiaToAE = Class.forName("thaumicenergistics.integration.appeng.AEEssentiaStack")
                    .getMethod("fromEssentiaStack", essentiaStackClass);
            if (essentiaChannel == null) {
                essentiaStackCtor = null;
                essentiaToAE = null;
            }
        } catch (Exception ignored) {
            essentiaChannel = null;
            essentiaStackCtor = null;
            essentiaToAE = null;
        }
    }

    private static long invokeLong(Method method, Object... args) {
        if (method == null) {
            return 0;
        }
        try {
            Object result = method.invoke(null, args);
            return result instanceof Long ? (Long) result : 0L;
        } catch (Exception e) {
            return 0;
        }
    }
}

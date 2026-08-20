package com.github.aeddddd.mmceaddition.compat;

import appeng.api.networking.security.IActionHost;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Method;

/**
 * AE2Enhanced 网络 RF 能源 API 的反射桥（软依赖）。
 * <p>
 * MMCE-addition 不直接编译依赖 AE2Enhanced，而是在运行时通过反射调用其
 * {@code com.github.aeddddd.ae2enhanced.api.NetworkEnergyApi}：
 * <ul>
 *   <li>未安装 AE2Enhanced 时，{@link #isAvailable()} 返回 false，所有查询/提取返回 0</li>
 *   <li>反射 Method 在首次调用时缓存，后续调用只有一次虚反射开销</li>
 * </ul>
 */
public final class NetworkEnergyCompat {

    private static final String MODID = "ae2enhanced";
    private static final String API_CLASS = "com.github.aeddddd.ae2enhanced.api.NetworkEnergyApi";

    private static boolean initialized = false;
    private static boolean apiReady = false;
    private static Method getStoredEnergyMethod;
    private static Method extractEnergyMethod;
    /**
     * 网络 RF 回注方法（ae2e 扩展 API，旧版本可能不存在）。
     */
    private static Method insertEnergyMethod;
    private static boolean insertReady = false;

    private NetworkEnergyCompat() {
    }

    /**
     * @return AE2Enhanced 已安装且 API 可用
     */
    public static boolean isAvailable() {
        ensureInit();
        return apiReady;
    }

    /**
     * 查询宿主所在网络当前存储的 RF 总量。
     *
     * @return 网络 RF 总量；API 不可用或查询失败时返回 0
     */
    public static long getStoredEnergy(IActionHost host) {
        if (!isAvailable() || host == null) {
            return 0;
        }
        try {
            Object result = getStoredEnergyMethod.invoke(null, host);
            return result instanceof Long ? (Long) result : 0L;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从宿主所在网络提取 RF。
     *
     * @param simulate true = 仅模拟，不实际扣除
     * @return 实际（或模拟可）提取到的 RF 数量；API 不可用时返回 0
     */
    public static long extractEnergy(IActionHost host, long amount, boolean simulate) {
        if (!isAvailable() || host == null || amount <= 0) {
            return 0;
        }
        try {
            Object result = extractEnergyMethod.invoke(null, host, amount, simulate);
            return result instanceof Long ? (Long) result : 0L;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * @return AE2Enhanced 已安装且回注 API（insertEnergy）可用。
     * <p>
     * insertEnergy 是 ae2e 后期扩展的方法，旧版本 ae2e 只有查询/提取；
     * 与 {@link #isAvailable()} 独立探测，缺失时能源输出优雅失效，其余功能不受影响。
     */
    public static boolean isInsertAvailable() {
        ensureInit();
        return insertReady;
    }

    /**
     * 向宿主所在网络回注 RF。
     *
     * @param simulate true = 仅模拟，不实际注入
     * @return 实际（或模拟可）注入的 RF 数量；API 不可用时返回 0
     */
    public static long insertEnergy(IActionHost host, long amount, boolean simulate) {
        if (!isInsertAvailable() || host == null || amount <= 0) {
            return 0;
        }
        try {
            Object result = insertEnergyMethod.invoke(null, host, amount, simulate);
            return result instanceof Long ? (Long) result : 0L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void ensureInit() {
        if (!initialized) {
            synchronized (NetworkEnergyCompat.class) {
                if (!initialized) {
                    init();
                    initialized = true;
                }
            }
        }
    }

    private static void init() {
        if (!Loader.isModLoaded(MODID)) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            getStoredEnergyMethod = apiClass.getMethod("getStoredEnergy", IActionHost.class);
            extractEnergyMethod = apiClass.getMethod("extractEnergy", IActionHost.class, long.class, boolean.class);
            apiReady = true;
            try {
                insertEnergyMethod = apiClass.getMethod("insertEnergy", IActionHost.class, long.class, boolean.class);
                insertReady = true;
            } catch (Exception ignored) {
                // 旧版 ae2e 没有 insertEnergy：能源输出不可用，能源输入/查询不受影响
            }
        } catch (Exception ignored) {
            // API 类缺失或签名不匹配：视为不可用，能源仓表现为 0 能量
        }
    }
}

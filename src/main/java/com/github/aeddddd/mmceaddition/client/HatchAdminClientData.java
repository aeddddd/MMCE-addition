package com.github.aeddddd.mmceaddition.client;

import com.github.aeddddd.mmceaddition.network.PktHatchAdminSync;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓室管理工具的客户端数据缓存。
 * <p>
 * {@link PktHatchAdminSync} 到达后存入这里，GUI 每帧直接读取。
 * 纯客户端状态，仅主线程访问。
 */
@SideOnly(Side.CLIENT)
public final class HatchAdminClientData {

    private HatchAdminClientData() {
    }

    public static boolean profilerEnabled;

    // ---- 服务器整体 ----
    public static double serverMspt;
    public static double serverTps;
    /** 逐 tick 耗时历史（微秒，时间序）。 */
    public static int[] serverTickHistory = new int[0];

    // ---- 异步输出管理器 ----
    public static long mgrCycleMicros;
    public static long mgrInjectedItems;
    public static long mgrInjectedFluids;
    public static long mgrTotalCycles;
    public static long mgrTotalInjectedItems;
    public static long mgrTotalInjectedFluids;
    public static long mgrTotalOps;
    public static final int[] mgrRegistered = new int[4];
    public static final int[] mgrDirty = new int[4];
    public static long[] mgrCycleHistory = new long[0];
    public static long[] mgrItemsHistory = new long[0];
    public static long[] mgrFluidsHistory = new long[0];
    public static int[] mgrOpsHistory = new int[0];

    // ---- 仓室 tick 采样 ----
    /** 每秒全部采样仓室 tick 总耗时历史（纳秒，时间序；采样关闭时为空）。 */
    public static long[] profilerNanosHistory = new long[0];

    // ---- 仓室条目 ----
    public static int totalFound;
    public static int onlineCount;
    public static int offlineCount;
    public static List<PktHatchAdminSync.EntryData> entries = new ArrayList<>();

    /** 上次同步到达的时间戳（毫秒）。 */
    public static long lastSyncAtMs;

    /** 由 {@link PktHatchAdminSync.Handler} 在客户端主线程调用。 */
    public static void accept(PktHatchAdminSync sync) {
        profilerEnabled = sync.profilerEnabled;
        serverMspt = sync.serverMspt;
        serverTps = sync.serverTps;
        serverTickHistory = sync.serverTickHistory;
        mgrCycleMicros = sync.mgrCycleMicros;
        mgrInjectedItems = sync.mgrInjectedItems;
        mgrInjectedFluids = sync.mgrInjectedFluids;
        mgrTotalCycles = sync.mgrTotalCycles;
        mgrTotalInjectedItems = sync.mgrTotalInjectedItems;
        mgrTotalInjectedFluids = sync.mgrTotalInjectedFluids;
        mgrTotalOps = sync.mgrTotalOps;
        System.arraycopy(sync.mgrRegistered, 0, mgrRegistered, 0, 4);
        System.arraycopy(sync.mgrDirty, 0, mgrDirty, 0, 4);
        mgrCycleHistory = sync.mgrCycleHistory;
        mgrItemsHistory = sync.mgrItemsHistory;
        mgrFluidsHistory = sync.mgrFluidsHistory;
        mgrOpsHistory = sync.mgrOpsHistory;
        profilerNanosHistory = sync.profilerNanosHistory;
        totalFound = sync.totalFound;
        onlineCount = sync.onlineCount;
        offlineCount = sync.offlineCount;
        entries = sync.entries;
        lastSyncAtMs = System.currentTimeMillis();
    }
}

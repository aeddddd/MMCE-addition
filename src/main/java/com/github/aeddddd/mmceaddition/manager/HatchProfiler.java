package com.github.aeddddd.mmceaddition.manager;

import net.minecraft.tileentity.TileEntity;

import java.util.IdentityHashMap;

/**
 * 仓室 tick 性能采样器。
 * <p>
 * 由 Mixin 在 MMCE 仓室/控制器的 tick 方法头尾插桩调用：
 * HEAD 处记录 {@link #tickStart}，RETURN 处调用 {@link #record} 累计耗时。
 * 所有插桩点都运行在主线程，因此起始时间用静态字段传递即可。
 * <p>
 * 采样默认关闭（零开销）；开启时清空历史数据重新开始累计。
 * 统计口径：
 * <ul>
 *   <li>单仓室：开启以来的平均每 tick 耗时（纳秒精度，{@link #avgNanos}）</li>
 *   <li>全局：每秒所有采样仓室的 tick 总耗时/调用次数，60 秒环形历史（变化曲线数据源）</li>
 * </ul>
 */
public enum HatchProfiler {
    INSTANCE;

    /**
     * tick 方法 HEAD 处记录的起始时间（纳秒）。
     * 仅在主线程使用，无需同步。
     */
    public static long tickStart;

    /** 采样开关。volatile 保证网络线程切换后主线程立即可见。 */
    private volatile boolean enabled = false;

    /** 每个 TileEntity 的累计统计（仅主线程读写）。 */
    private final IdentityHashMap<TileEntity, Stat> stats = new IdentityHashMap<>();

    // ==================== 全局历史曲线 ====================

    /** 历史长度（秒）。 */
    private static final int HISTORY = 60;

    /** 每秒全部采样仓室的 tick 总耗时（纳秒）环形缓冲。 */
    private final long[] historySecondNanos = new long[HISTORY];
    /** 每秒采样调用次数环形缓冲。 */
    private final int[] historySecondCalls = new int[HISTORY];
    /** 下一个写入位置（环形）。 */
    private int historyIndex;
    /** 当前秒的累计。 */
    private long secondNanos;
    private int secondCalls;
    private long secondStartMs = System.currentTimeMillis();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 切换采样开关。开启时清空历史数据，保证统计窗口从开启时刻起算。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            stats.clear();
            secondNanos = 0;
            secondCalls = 0;
            secondStartMs = System.currentTimeMillis();
        }
    }

    /**
     * 记录一次 tick 耗时。
     *
     * @param te    被插桩的 TileEntity
     * @param nanos 本次 tick 耗时（纳秒）
     */
    public void record(TileEntity te, long nanos) {
        if (!enabled || te == null) {
            return;
        }
        Stat stat = stats.get(te);
        if (stat == null) {
            stat = new Stat();
            stats.put(te, stat);
        }
        stat.totalNanos += nanos;
        stat.calls++;
        secondNanos += nanos;
        secondCalls++;
        flushSecondIfNeeded();
    }

    /** 每秒把当前秒累计写入环形历史。 */
    private void flushSecondIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - secondStartMs < 1000) {
            return;
        }
        historySecondNanos[historyIndex] = secondNanos;
        historySecondCalls[historyIndex] = secondCalls;
        historyIndex = (historyIndex + 1) % HISTORY;
        secondNanos = 0;
        secondCalls = 0;
        secondStartMs = now;
    }

    /** 每秒 tick 总耗时历史（纳秒，按时间先后排序，供曲线绘制）。 */
    public long[] historySecondNanosChronological() {
        long[] out = new long[HISTORY];
        for (int i = 0; i < HISTORY; i++) {
            out[i] = historySecondNanos[(historyIndex + i) % HISTORY];
        }
        return out;
    }

    /** 每秒调用次数历史（与 {@link #historySecondNanosChronological()} 对齐）。 */
    public int[] historySecondCallsChronological() {
        int[] out = new int[HISTORY];
        for (int i = 0; i < HISTORY; i++) {
            out[i] = historySecondCalls[(historyIndex + i) % HISTORY];
        }
        return out;
    }

    /**
     * 查询某 TileEntity 的平均每 tick 耗时（纳秒）。无数据返回 0。
     */
    public long avgNanos(TileEntity te) {
        Stat stat = stats.get(te);
        if (stat == null || stat.calls == 0) {
            return 0;
        }
        return stat.totalNanos / stat.calls;
    }

    /** 清理已失效 TileEntity 的统计数据，防止泄漏。 */
    public void cleanInvalid() {
        stats.keySet().removeIf(TileEntity::isInvalid);
    }

    /** 单 TileEntity 的累计耗时与调用次数。 */
    private static final class Stat {
        long totalNanos;
        long calls;
    }
}

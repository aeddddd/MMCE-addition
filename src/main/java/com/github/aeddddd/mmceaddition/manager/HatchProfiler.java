package com.github.aeddddd.mmceaddition.manager;

import net.minecraft.tileentity.TileEntity;

import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * 仓室 tick 性能采样器。
 * <p>
 * 由 Mixin 在 MMCE 仓室/控制器的 tick 方法头尾插桩调用：
 * HEAD 处调用 {@link #begin(int)} 记录本插桩点的起点，RETURN 处调用 {@link #end(int)}
 * 取回本次耗时并累加进该点的统计，再调用 {@link #record} 记入单个 TileEntity。
 * 所有插桩点都运行在主线程（仓室 tick 与控制器 tick 都在服务端主线程），
 * 因此计时起点用静态数组传递（{@link #TICK_START}），无需同步。
 * 若将来出现跨线程调用，另一线程的 {@code begin} 会覆盖该点的起点，
 * 使本次耗时读数失真（可能极小甚至为负）；数组下标写入本身不会破坏其它内存结构。
 * <p>
 * 每个插桩点各占一个独立槽位，且用哨兵值标记"未在计时"，因此：
 * <ul>
 *   <li>嵌套调用（内层方法的 HEAD 不会覆盖外层的起点）互不污染；</li>
 *   <li>同一方法被重复注入（HEAD/RETURN 各触发两次）时，第二次 {@link #end(int)} 安全 no-op，
 *       不会重复计数；</li>
 *   <li>HEAD 取消 / 异常中断后未归零的槽位由 {@link #abort(int)} 显式清理。</li>
 * </ul>
 * <p>
 * 采样默认关闭（零开销）；开启时清空历史数据重新开始累计。
 * 统计口径：
 * <ul>
 *   <li>单仓室：开启以来的平均每 tick 耗时（纳秒精度，{@link #avgNanos}）</li>
 *   <li>单插桩点：开启以来的累计耗时/调用次数/平均耗时（{@link #pointAvgNanos(int)}）</li>
 *   <li>全局：每秒所有插桩点的 tick 总耗时/调用次数，60 秒环形历史（变化曲线数据源）</li>
 * </ul>
 */
public enum HatchProfiler {
    INSTANCE;

    // ==================== 插桩点 ====================

    /** 插桩点：MMCE 物品输入总线 {@code doRestrictedTick}。 */
    public static final int POINT_ITEM_INPUT_BUS = 0;
    /** 插桩点：MMCE 物品输出总线 {@code doRestrictedTick}。 */
    public static final int POINT_ITEM_OUTPUT_BUS = 1;
    /** 插桩点：MMCE 能源输出仓 {@code func_73660_a}/{@code update}。 */
    public static final int POINT_ENERGY_OUTPUT_HATCH = 2;
    /** 插桩点：MMCE 机器控制器 {@code doControllerTick}。 */
    public static final int POINT_MACHINE_CONTROLLER = 3;
    /** 插桩点数量。新增插桩点时同步扩充本常量与 {@link #TICK_START}。 */
    public static final int POINT_COUNT = 4;

    /** 哨兵值：该插桩点当前未在计时（从未 begin、已 end 或被 abort）。 */
    private static final long NOT_TIMING = -1L;

    /**
     * 各插桩点独立的起始时间（纳秒），下标为 {@code POINT_*} 常量；
     * 值为 {@link #NOT_TIMING} 表示该点当前未在计时。
     * <p>
     * 静态数组 + 下标读写，热路径零分配、无装箱。
     */
    private static final long[] TICK_START = newNotTimingArray();

    private static long[] newNotTimingArray() {
        long[] starts = new long[POINT_COUNT];
        Arrays.fill(starts, NOT_TIMING);
        return starts;
    }

    /** 采样开关。volatile 保证网络线程切换后主线程立即可见。 */
    private volatile boolean enabled = false;

    /** 每个 TileEntity 的累计统计（仅主线程读写）。 */
    private final IdentityHashMap<TileEntity, Stat> stats = new IdentityHashMap<>();

    /** 各插桩点自开启以来的累计耗时（纳秒），下标同 {@code POINT_*}。 */
    private final long[] pointNanos = new long[POINT_COUNT];
    /** 各插桩点自开启以来的累计调用次数。 */
    private final long[] pointCalls = new long[POINT_COUNT];

    // ==================== 全局历史曲线 ====================

    /** 历史长度（秒）。 */
    private static final int HISTORY = 60;

    /**
     * 每秒全部插桩点的 tick 总耗时（纳秒）环形缓冲。
     * <p>
     * 口径保持"全局聚合"（含控制器 tick）：GUI 的第三条曲线表达的就是仓室/机器的
     * 总 tick 开销，需要的是总量而非分解；按插桩点分解由 {@link #pointNanos} 提供，
     * 秒级分解会改动网络包字段，不在本次改动范围内。
     */
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
     * 切换采样开关。开启时清空历史数据与按点累计，保证统计窗口从开启时刻起算。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            stats.clear();
            Arrays.fill(pointNanos, 0L);
            Arrays.fill(pointCalls, 0L);
            secondNanos = 0;
            secondCalls = 0;
            secondStartMs = System.currentTimeMillis();
        }
    }

    /**
     * 插桩点 HEAD：记录该点的起始时间。采样关闭时只有一次 volatile 读，无分配。
     *
     * @param point {@code POINT_*} 常量
     */
    public void begin(int point) {
        if (!enabled) {
            return;
        }
        TICK_START[point] = System.nanoTime();
    }

    /**
     * 插桩点 RETURN：结束该点计时，把耗时累加进该点的累计并返回本次耗时。
     * 未在计时时（采样关闭、未 begin、已 end，或被 {@link #abort(int)} 清理）安全 no-op。
     *
     * @param point {@code POINT_*} 常量
     * @return 本次耗时（纳秒）；未在计时返回 -1，调用方据此跳过 {@link #record}
     */
    public long end(int point) {
        if (!enabled) {
            return -1L;
        }
        long start = TICK_START[point];
        if (start == NOT_TIMING) {
            return -1L;
        }
        TICK_START[point] = NOT_TIMING;
        long elapsed = System.nanoTime() - start;
        pointNanos[point] += elapsed;
        pointCalls[point]++;
        return elapsed;
    }

    /**
     * 放弃该插桩点当前这一次计时（HEAD 取消 tick 时调用），保证被取消的 tick
     * 既不计入该点统计，也不会被后续 RETURN 回调误当成一次完成的 tick。
     *
     * @param point {@code POINT_*} 常量
     */
    public void abort(int point) {
        if (!enabled) {
            return;
        }
        TICK_START[point] = NOT_TIMING;
    }

    /** 某插桩点自开启以来的累计耗时（纳秒）。 */
    public long pointTotalNanos(int point) {
        return pointNanos[point];
    }

    /** 某插桩点自开启以来的累计调用次数。 */
    public long pointCallCount(int point) {
        return pointCalls[point];
    }

    /** 某插桩点自开启以来的平均每次耗时（纳秒）。无数据返回 0。 */
    public long pointAvgNanos(int point) {
        long calls = pointCalls[point];
        if (calls == 0) {
            return 0;
        }
        return pointNanos[point] / calls;
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

package com.github.aeddddd.mmceaddition.manager;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 服务器整体 tick 监控器。
 * <p>
 * 在 {@link TickEvent.ServerTickEvent} 的 START/END 阶段测量整 tick 耗时，
 * 维护最近 100 tick（约 5 秒）的耗时环形历史，并给出窗口平均 MSPT 与 TPS。
 * 作为仓室管理工具性能页的"总体性能"基准线：仓室耗时曲线应与服务器
 * 整体曲线对照阅读。
 * <p>
 * 在 {@code CommonProxy.preInit} 注册到事件总线；常驻运行，开销仅为每 tick
 * 两次 {@code System.nanoTime()}。
 */
public enum ServerTickMonitor {
    INSTANCE;

    /** 历史长度（tick 数，20 tick/s 下约 5 秒）。 */
    private static final int HISTORY = 100;

    /** 每 tick 耗时（微秒）环形缓冲。 */
    private final int[] historyMicros = new int[HISTORY];
    /** 下一个写入位置（环形）。 */
    private int index;
    /** 已写入的样本数（未满 100 时小于 HISTORY）。 */
    private int count;

    private long tickStartNanos;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
        } else {
            int micros = (int) ((System.nanoTime() - tickStartNanos) / 1000);
            historyMicros[index] = micros;
            index = (index + 1) % HISTORY;
            if (count < HISTORY) {
                count++;
            }
        }
    }

    /** 每 tick 耗时历史（微秒，按时间先后排序，供曲线绘制）。 */
    public int[] historyMicrosChronological() {
        int[] out = new int[HISTORY];
        for (int i = 0; i < HISTORY; i++) {
            out[i] = historyMicros[(index + i) % HISTORY];
        }
        return out;
    }

    /** 窗口平均单 tick 耗时（毫秒）。 */
    public double avgMspt() {
        if (count == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < HISTORY; i++) {
            sum += historyMicros[i];
        }
        return sum / (double) count / 1000.0;
    }

    /** 由平均 MSPT 推算的 TPS（上限 20）。 */
    public double tps() {
        double mspt = avgMspt();
        return mspt <= 0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
    }
}

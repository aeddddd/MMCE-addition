package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.client.HatchAdminClientData;
import com.github.aeddddd.mmceaddition.manager.HatchAdminManager;
import com.github.aeddddd.mmceaddition.manager.HatchProfiler;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import com.github.aeddddd.mmceaddition.manager.ServerTickMonitor;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：仓室管理工具全量数据同步。
 * <p>
 * 包含三类数据：
 * <ul>
 *   <li>仓室条目列表（经过滤/排序/截断）+ 在线/离线统计</li>
 *   <li>服务器整体性能：平均 MSPT / TPS + 逐 tick 耗时历史（变化曲线）</li>
 *   <li>异步输出管理器统计：周期耗时/注入量/注入次数的实时值、累计值与逐周期历史</li>
 *   <li>仓室 tick 采样：开关状态 + 每秒总耗时历史（采样开启时）</li>
 * </ul>
 */
public class PktHatchAdminSync implements IMessage {

    /** 性能采样是否开启。 */
    public boolean profilerEnabled;

    // ---- 服务器整体 ----
    /** 窗口平均单 tick 耗时（毫秒）。 */
    public double serverMspt;
    /** 推算 TPS。 */
    public double serverTps;
    /** 逐 tick 耗时历史（微秒，时间序）。 */
    public int[] serverTickHistory = new int[0];

    // ---- 异步输出管理器 ----
    public long mgrCycleMicros;
    public long mgrInjectedItems;
    public long mgrInjectedFluids;
    public long mgrTotalCycles;
    public long mgrTotalInjectedItems;
    public long mgrTotalInjectedFluids;
    public long mgrTotalOps;
    /** 各类注册数：[物品总线, 流体仓, 样板总成, 输出总成]。 */
    public int[] mgrRegistered = new int[4];
    /** 各类待处理积压数，顺序同上。 */
    public int[] mgrDirty = new int[4];
    /** 周期耗时历史（微秒，时间序）。 */
    public long[] mgrCycleHistory = new long[0];
    /** 每周期注入物品数历史（时间序）。 */
    public long[] mgrItemsHistory = new long[0];
    /** 每周期注入流体量历史（mB，时间序）。 */
    public long[] mgrFluidsHistory = new long[0];
    /** 每周期注入操作次数历史（时间序）。 */
    public int[] mgrOpsHistory = new int[0];

    // ---- 仓室 tick 采样 ----
    /** 每秒全部采样仓室 tick 总耗时历史（纳秒，时间序）。 */
    public long[] profilerNanosHistory = new long[0];

    // ---- 仓室条目 ----
    /** 过滤后总数（可能大于 entries.size()，因条目数被截断）。 */
    public int totalFound;
    /** 类型范围内 AE 仓室在线数（不受网络过滤影响）。 */
    public int onlineCount;
    /** 同上，离线数。 */
    public int offlineCount;
    /** 仓室条目列表。 */
    public List<EntryData> entries = new ArrayList<>();

    /** 客户端可读的条目镜像（与 {@link HatchAdminManager.Entry} 字段一致）。 */
    public static final class EntryData {
        public BlockPos pos;
        public int dim;
        public int typeOrdinal;
        public byte autoIO;
        public byte network;
        public long buffered;
        /** 平均每 tick 耗时（纳秒）。 */
        public long avgNanos;
        public String name = "";
        public String icon = "";
        public int meta;
    }

    public PktHatchAdminSync() {
    }

    /**
     * 服务端构建：扫描世界并汇总性能统计。
     */
    public static PktHatchAdminSync build(World world, int typeMask, int netFilter, int sortBy) {
        PktHatchAdminSync pkt = new PktHatchAdminSync();
        pkt.profilerEnabled = HatchProfiler.INSTANCE.isEnabled();
        pkt.profilerNanosHistory = pkt.profilerEnabled
                ? HatchProfiler.INSTANCE.historySecondNanosChronological() : new long[0];

        ServerTickMonitor monitor = ServerTickMonitor.INSTANCE;
        pkt.serverMspt = monitor.avgMspt();
        pkt.serverTps = monitor.tps();
        pkt.serverTickHistory = monitor.historyMicrosChronological();

        MEAsyncOutputManager mgr = MEAsyncOutputManager.INSTANCE;
        pkt.mgrCycleMicros = mgr.getStatLastCycleMicros();
        pkt.mgrInjectedItems = mgr.getStatLastInjectedItems();
        pkt.mgrInjectedFluids = mgr.getStatLastInjectedFluids();
        pkt.mgrTotalCycles = mgr.getStatTotalCycles();
        pkt.mgrTotalInjectedItems = mgr.getStatTotalInjectedItems();
        pkt.mgrTotalInjectedFluids = mgr.getStatTotalInjectedFluids();
        pkt.mgrTotalOps = mgr.getStatTotalOps();
        pkt.mgrRegistered = mgr.getRegisteredCounts();
        pkt.mgrDirty = mgr.getDirtyCounts();
        pkt.mgrCycleHistory = mgr.getHistoryCycleMicros();
        pkt.mgrItemsHistory = mgr.getHistoryInjectedItems();
        pkt.mgrFluidsHistory = mgr.getHistoryInjectedFluids();
        pkt.mgrOpsHistory = mgr.getHistoryInjectedOps();

        HatchAdminManager.ScanResult result = HatchAdminManager.scan(world, typeMask, netFilter, sortBy);
        pkt.totalFound = result.totalFound;
        pkt.onlineCount = result.onlineCount;
        pkt.offlineCount = result.offlineCount;
        for (HatchAdminManager.Entry e : result.entries) {
            EntryData data = new EntryData();
            data.pos = e.pos;
            data.dim = e.dim;
            data.typeOrdinal = e.type.ordinal();
            data.autoIO = e.autoIO;
            data.network = e.network;
            data.buffered = e.buffered;
            data.avgNanos = e.avgNanos;
            data.name = e.name;
            data.icon = e.icon;
            data.meta = e.meta;
            pkt.entries.add(data);
        }
        return pkt;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        profilerEnabled = buf.readBoolean();
        serverMspt = buf.readDouble();
        serverTps = buf.readDouble();
        serverTickHistory = readIntArray(buf);
        mgrCycleMicros = buf.readLong();
        mgrInjectedItems = buf.readLong();
        mgrInjectedFluids = buf.readLong();
        mgrTotalCycles = buf.readLong();
        mgrTotalInjectedItems = buf.readLong();
        mgrTotalInjectedFluids = buf.readLong();
        mgrTotalOps = buf.readLong();
        for (int i = 0; i < 4; i++) {
            mgrRegistered[i] = buf.readInt();
        }
        for (int i = 0; i < 4; i++) {
            mgrDirty[i] = buf.readInt();
        }
        mgrCycleHistory = readLongArray(buf);
        mgrItemsHistory = readLongArray(buf);
        mgrFluidsHistory = readLongArray(buf);
        mgrOpsHistory = readIntArray(buf);
        profilerNanosHistory = readLongArray(buf);
        totalFound = buf.readInt();
        onlineCount = buf.readInt();
        offlineCount = buf.readInt();
        int count = buf.readInt();
        entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            EntryData data = new EntryData();
            data.pos = BlockPos.fromLong(buf.readLong());
            data.dim = buf.readInt();
            data.typeOrdinal = buf.readByte();
            data.autoIO = buf.readByte();
            data.network = buf.readByte();
            data.buffered = buf.readLong();
            data.avgNanos = buf.readLong();
            data.name = ByteBufUtils.readUTF8String(buf);
            data.icon = ByteBufUtils.readUTF8String(buf);
            data.meta = buf.readInt();
            entries.add(data);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(profilerEnabled);
        buf.writeDouble(serverMspt);
        buf.writeDouble(serverTps);
        writeIntArray(buf, serverTickHistory);
        buf.writeLong(mgrCycleMicros);
        buf.writeLong(mgrInjectedItems);
        buf.writeLong(mgrInjectedFluids);
        buf.writeLong(mgrTotalCycles);
        buf.writeLong(mgrTotalInjectedItems);
        buf.writeLong(mgrTotalInjectedFluids);
        buf.writeLong(mgrTotalOps);
        for (int i = 0; i < 4; i++) {
            buf.writeInt(mgrRegistered[i]);
        }
        for (int i = 0; i < 4; i++) {
            buf.writeInt(mgrDirty[i]);
        }
        writeLongArray(buf, mgrCycleHistory);
        writeLongArray(buf, mgrItemsHistory);
        writeLongArray(buf, mgrFluidsHistory);
        writeIntArray(buf, mgrOpsHistory);
        writeLongArray(buf, profilerNanosHistory);
        buf.writeInt(totalFound);
        buf.writeInt(onlineCount);
        buf.writeInt(offlineCount);
        buf.writeInt(entries.size());
        for (EntryData data : entries) {
            buf.writeLong(data.pos.toLong());
            buf.writeInt(data.dim);
            buf.writeByte(data.typeOrdinal);
            buf.writeByte(data.autoIO);
            buf.writeByte(data.network);
            buf.writeLong(data.buffered);
            buf.writeLong(data.avgNanos);
            ByteBufUtils.writeUTF8String(buf, data.name);
            ByteBufUtils.writeUTF8String(buf, data.icon);
            buf.writeInt(data.meta);
        }
    }

    private static void writeIntArray(ByteBuf buf, int[] array) {
        buf.writeInt(array.length);
        for (int v : array) {
            buf.writeInt(v);
        }
    }

    private static int[] readIntArray(ByteBuf buf) {
        int[] out = new int[buf.readInt()];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readInt();
        }
        return out;
    }

    private static void writeLongArray(ByteBuf buf, long[] array) {
        buf.writeInt(array.length);
        for (long v : array) {
            buf.writeLong(v);
        }
    }

    private static long[] readLongArray(ByteBuf buf) {
        long[] out = new long[buf.readInt()];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readLong();
        }
        return out;
    }

    public static class Handler implements IMessageHandler<PktHatchAdminSync, IMessage> {

        @Override
        public IMessage onMessage(PktHatchAdminSync message, MessageContext ctx) {
            receive(message);
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void receive(PktHatchAdminSync message) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(
                    () -> HatchAdminClientData.accept(message));
        }
    }
}

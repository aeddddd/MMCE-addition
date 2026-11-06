package com.github.aeddddd.mmceaddition.manager;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.fluids.util.AEFluidStack;
import appeng.me.GridAccessException;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncFluidOutputHatch;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
import com.github.aeddddd.mmceaddition.tile.TileMEOutputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.tile.slot.PatternAssemblySlot;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步 ME 输出管理器。
 * <p>
 * 核心设计思想：
 * <ul>
 *   <li>每个异步输出方块不再单独注册为 AE2 的 IGridTickable，避免大量节点占用网格 tick。</li>
 *   <li>所有方块把产出先缓冲到本地，然后由这个单一管理器统一调度、批量注入。</li>
 *   <li>只处理缓冲区非空的“脏”方块，而不是每 tick 扫描所有注册方块。</li>
 *   <li>按 AE 网格分组，每个网格每 tick 只查一次 IStorageGrid / IEnergyGrid。</li>
 * </ul>
 * <p>
 * 限流与配额语义：
 * <ul>
 *   <li>配置项 {@code max_tiles_per_tick} 的含义是“每类仓室每个注入周期最多处理的方块数”，
 *       而不是全类合计：物品总线、流体仓、ME 样板总成、ME 输出总成仓各自持有一份独立预算，
 *       因此一个注入周期内实际最多处理 4 × max_tiles_per_tick 个方块。注入周期为
 *       {@code injectionInterval} 个 server tick（不是每 tick 都注入）。</li>
 *   <li>限流按轮转推进（见 {@link Rotation}）：因配额被截断的方块留在脏集合里，
 *       下一个周期从上次处理到的位置之后继续收集，因此任何缓冲非空的方块都不会被
 *       排在它前面的方块永久饿死。</li>
 * </ul>
 * <p>
 * 唯一的产出丢弃路径：产出无法表示为 AE 网络资源时（AE2 拒绝包装该物品/流体），
 * 会把该变体从本地缓冲区移除并记一条限流的 warn 日志，见
 * {@link #processItemBusItem} / {@link #processFluidHatchFluid}。
 */
public enum MEAsyncOutputManager {
    INSTANCE;

    /**
     * 产出被丢弃时的日志节流间隔（注入周期数）。
     * <p>
     * 同一个方块在这么多周期内最多记录一条丢弃日志，避免“网络无法表示的资源”
     * 在热路径上反复刷屏。按默认 injectionInterval = 5 tick 计算，约合 25 秒一条。
     */
    private static final int DISCARD_LOG_INTERVAL_CYCLES = 100;

    /**
     * 所有已注册的异步物品总线。
     */
    private final Set<TileMEAsyncItemOutputBus> itemBuses = ConcurrentHashMap.newKeySet();

    /**
     * 所有已注册的异步流体仓。
     */
    private final Set<TileMEAsyncFluidOutputHatch> fluidHatches = ConcurrentHashMap.newKeySet();

    /**
     * 所有已注册的 ME 样板总成。
     */
    private final Set<TileMEPatternAssembly> patternAssemblies = ConcurrentHashMap.newKeySet();

    /**
     * 所有已注册的 ME 输出总成仓。
     */
    private final Set<TileMEOutputAssembly> outputAssemblies = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的物品总线：只有缓冲区非空的 tile 才会在这里。
     */
    private final Set<TileMEAsyncItemOutputBus> dirtyItemBuses = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的流体仓。
     */
    private final Set<TileMEAsyncFluidOutputHatch> dirtyFluidHatches = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的 ME 样板总成。
     */
    private final Set<TileMEPatternAssembly> dirtyPatternAssemblies = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的 ME 输出总成仓。
     */
    private final Set<TileMEOutputAssembly> dirtyOutputAssemblies = ConcurrentHashMap.newKeySet();

    /**
     * 物品总线的限流轮转状态，见 {@link Rotation}。只在服务端主线程的注入周期内访问。
     */
    private final Rotation itemRotation = new Rotation();

    /**
     * 流体仓的限流轮转状态，见 {@link Rotation}。
     */
    private final Rotation fluidRotation = new Rotation();

    /**
     * ME 样板总成的限流轮转状态，见 {@link Rotation}。
     */
    private final Rotation patternAssemblyRotation = new Rotation();

    /**
     * ME 输出总成仓的限流轮转状态，见 {@link Rotation}。
     */
    private final Rotation outputAssemblyRotation = new Rotation();

    /**
     * 丢弃日志节流表：键为产出所属方块（弱引用，方块卸载后条目自动失效，不会阻止回收），
     * 值为上次记录日志时所在的注入周期号；物品与流体共用同一份节流。
     * <p>
     * 只在服务端主线程的注入路径（{@link #processItemBusItem} / {@link #processFluidHatchFluid}）访问，
     * 不需要同步。
     */
    private final Map<Object, Long> discardLogCycle = new WeakHashMap<>();

    /**
     * AE2 物品存储通道，用于创建 IAEItemStack 和获取 IMEMonitor。
     */
    private final IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);

    /**
     * AE2 流体存储通道。
     */
    private final IFluidStorageChannel fluidChannel = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

    /**
     * 服务端 tick 计数器，用于按配置间隔批量注入。
     */
    private int tickCounter = 0;

    /**
     * 全量兜底扫描间隔（tick）。
     * <p>
     * 脏标记链路的任何遗漏（极端时序、跨线程写入、异常中断等）都会导致缓冲内容
     * 永远不被处理；每隔一段时间全量扫描一次所有已注册方块，保证自愈。
     */
    private static final int RESCAN_INTERVAL = 100;

    /**
     * 兜底扫描计数器。
     */
    private int rescanCounter = 0;

    // ==================== 运行统计（仓室管理工具性能页数据源） ====================

    /** 上一注入周期的总耗时（微秒）。 */
    private long statLastCycleMicros;
    /** 上一注入周期实际注入网络的物品数量。 */
    private long statLastInjectedItems;
    /** 上一注入周期实际注入网络的流体量（mB）。 */
    private long statLastInjectedFluids;
    /** 累计完成的注入周期数。 */
    private long statTotalCycles;
    /** 本周期累计注入物品数（周期结束时转移到 statLastInjectedItems）。 */
    private long cycleInjectedItems;
    /** 本周期累计注入流体量（mB）。 */
    private long cycleInjectedFluids;
    /** 本周期注入操作次数（每次成功注入一种物品/流体计一次）。 */
    private int cycleInjectedOps;
    /** 累计注入物品总数。 */
    private long statTotalInjectedItems;
    /** 累计注入流体总量（mB）。 */
    private long statTotalInjectedFluids;
    /** 累计注入操作次数。 */
    private long statTotalOps;

    /** 历史曲线长度（注入周期数）。 */
    private static final int HISTORY = 60;
    /** 每周期耗时（微秒）环形历史。 */
    private final long[] historyCycleMicros = new long[HISTORY];
    /** 每周期注入物品数环形历史。 */
    private final long[] historyInjectedItems = new long[HISTORY];
    /** 每周期注入流体量（mB）环形历史。 */
    private final long[] historyInjectedFluids = new long[HISTORY];
    /** 每周期注入操作次数环形历史。 */
    private final int[] historyInjectedOps = new int[HISTORY];
    /** 下一个写入位置（环形）。 */
    private int historyIndex;

    public long getStatTotalInjectedItems() {
        return statTotalInjectedItems;
    }

    public long getStatTotalInjectedFluids() {
        return statTotalInjectedFluids;
    }

    public long getStatTotalOps() {
        return statTotalOps;
    }

    /** 周期耗时历史（微秒，时间序）。 */
    public long[] getHistoryCycleMicros() {
        return copyLongHistory(historyCycleMicros);
    }

    /** 每周期注入物品数历史（时间序）。 */
    public long[] getHistoryInjectedItems() {
        return copyLongHistory(historyInjectedItems);
    }

    /** 每周期注入流体量历史（mB，时间序）。 */
    public long[] getHistoryInjectedFluids() {
        return copyLongHistory(historyInjectedFluids);
    }

    /** 每周期注入操作次数历史（时间序）。 */
    public int[] getHistoryInjectedOps() {
        int[] out = new int[HISTORY];
        for (int i = 0; i < HISTORY; i++) {
            out[i] = historyInjectedOps[(historyIndex + i) % HISTORY];
        }
        return out;
    }

    private long[] copyLongHistory(long[] src) {
        long[] out = new long[HISTORY];
        for (int i = 0; i < HISTORY; i++) {
            out[i] = src[(historyIndex + i) % HISTORY];
        }
        return out;
    }

    public long getStatLastCycleMicros() {
        return statLastCycleMicros;
    }

    public long getStatLastInjectedItems() {
        return statLastInjectedItems;
    }

    public long getStatLastInjectedFluids() {
        return statLastInjectedFluids;
    }

    public long getStatTotalCycles() {
        return statTotalCycles;
    }

    /** 各类仓室注册数量：[物品总线, 流体仓, 样板总成, 输出总成]。 */
    public int[] getRegisteredCounts() {
        return new int[]{itemBuses.size(), fluidHatches.size(), patternAssemblies.size(), outputAssemblies.size()};
    }

    /** 各类仓室当前待处理（脏）积压数量，顺序同 {@link #getRegisteredCounts()}。 */
    public int[] getDirtyCounts() {
        return new int[]{dirtyItemBuses.size(), dirtyFluidHatches.size(),
                dirtyPatternAssemblies.size(), dirtyOutputAssemblies.size()};
    }

    public void register(TileMEAsyncItemOutputBus bus) {
        itemBuses.add(bus);
    }

    public void unregister(TileMEAsyncItemOutputBus bus) {
        itemBuses.remove(bus);
        dirtyItemBuses.remove(bus);
    }

    public void register(TileMEAsyncFluidOutputHatch hatch) {
        fluidHatches.add(hatch);
    }

    public void unregister(TileMEAsyncFluidOutputHatch hatch) {
        fluidHatches.remove(hatch);
        dirtyFluidHatches.remove(hatch);
    }

    /**
     * 标记某个物品总线为待处理。
     * <p>
     * 由 TileEntity 在缓冲区从空变非空时调用。
     */
    public void markDirty(TileMEAsyncItemOutputBus bus) {
        if (itemBuses.contains(bus)) {
            dirtyItemBuses.add(bus);
        }
    }

    public void markDirty(TileMEAsyncFluidOutputHatch hatch) {
        if (fluidHatches.contains(hatch)) {
            dirtyFluidHatches.add(hatch);
        }
    }

    public void register(TileMEPatternAssembly assembly) {
        patternAssemblies.add(assembly);
    }

    public void unregister(TileMEPatternAssembly assembly) {
        patternAssemblies.remove(assembly);
        dirtyPatternAssemblies.remove(assembly);
    }

    public void markDirty(TileMEPatternAssembly assembly) {
        if (patternAssemblies.contains(assembly)) {
            dirtyPatternAssemblies.add(assembly);
        }
    }

    public void register(TileMEOutputAssembly assembly) {
        outputAssemblies.add(assembly);
    }

    public void unregister(TileMEOutputAssembly assembly) {
        outputAssemblies.remove(assembly);
        dirtyOutputAssemblies.remove(assembly);
    }

    public void markDirty(TileMEOutputAssembly assembly) {
        if (outputAssemblies.contains(assembly)) {
            dirtyOutputAssemblies.add(assembly);
        }
    }

    /**
     * 服务端 tick 事件处理器。
     * <p>
     * 只在 tick 阶段 END 执行，并根据配置 injectionInterval 决定本次是否注入。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        if (++rescanCounter >= RESCAN_INTERVAL) {
            rescanCounter = 0;
            rescanAll();
        }
        int interval = Math.max(1, MMCEAdditionConfig.injectionInterval);
        if (tickCounter % interval != 0) {
            return;
        }
        long cycleStart = System.nanoTime();
        cycleInjectedItems = 0;
        cycleInjectedFluids = 0;
        cycleInjectedOps = 0;
        processItemOutputs();
        processFluidOutputs();
        processPatternAssemblyOutputs();
        processOutputAssemblyOutputs();
        statLastCycleMicros = (System.nanoTime() - cycleStart) / 1000;
        statLastInjectedItems = cycleInjectedItems;
        statLastInjectedFluids = cycleInjectedFluids;
        statTotalCycles++;
        statTotalInjectedItems += cycleInjectedItems;
        statTotalInjectedFluids += cycleInjectedFluids;
        statTotalOps += cycleInjectedOps;
        historyCycleMicros[historyIndex] = statLastCycleMicros;
        historyInjectedItems[historyIndex] = cycleInjectedItems;
        historyInjectedFluids[historyIndex] = cycleInjectedFluids;
        historyInjectedOps[historyIndex] = cycleInjectedOps;
        historyIndex = (historyIndex + 1) % HISTORY;
    }

    /**
     * 全量兜底扫描：把所有缓冲非空的已注册方块重新加入待处理集合。
     * <p>
     * 正常情况下脏标记链路已经覆盖所有写入，本方法只是廉价保险。
     */
    private void rescanAll() {
        for (TileMEAsyncItemOutputBus bus : itemBuses) {
            if (!bus.isInvalid() && !bus.getItemBuffer().isEmpty()) {
                dirtyItemBuses.add(bus);
            }
        }
        for (TileMEAsyncFluidOutputHatch hatch : fluidHatches) {
            if (!hatch.isInvalid() && !hatch.getFluidBuffer().isEmpty()) {
                dirtyFluidHatches.add(hatch);
            }
        }
        for (TileMEPatternAssembly assembly : patternAssemblies) {
            if (!assembly.isInvalid() && assembly.hasAnyOutput()) {
                dirtyPatternAssemblies.add(assembly);
            }
        }
        for (TileMEOutputAssembly assembly : outputAssemblies) {
            if (!assembly.isInvalid() && assembly.hasAnyOutput()) {
                dirtyOutputAssemblies.add(assembly);
            }
        }
    }

    /**
     * 处理所有待处理的物品总线。
     */
    private void processItemOutputs() {
        if (dirtyItemBuses.isEmpty()) {
            return;
        }

        // 按 AE 网格分组，同一网格的多个总线共享一次 storage/energy 查询。
        Map<IGrid, List<TileMEAsyncItemOutputBus>> gridMap = new HashMap<>();
        int collected = 0;
        int maxTiles = getMaxTilesPerTick();

        Iterator<TileMEAsyncItemOutputBus> it = itemRotation.begin(dirtyItemBuses, maxTiles);
        while (it.hasNext() && (maxTiles <= 0 || collected < maxTiles)) {
            TileMEAsyncItemOutputBus bus = it.next();
            if (!isValid(bus)) {
                it.remove();
                itemBuses.remove(bus);
                continue;
            }
            if (bus.getItemBuffer().isEmpty()) {
                it.remove();
                continue;
            }
            try {
                IGrid grid = bus.getProxy().getGrid();
                gridMap.computeIfAbsent(grid, k -> new ArrayList<>()).add(bus);
                collected++;
            } catch (GridAccessException ignored) {
                // 尚未连接到网格，保留在 dirty 集合中等待下次尝试。
            }
        }
        // 轮转推进：若本轮被配额截断，下个周期从本轮处理到的位置之后继续，
        // 避免尾部的仓室永远轮不到（被截断的 bus 仍在 dirtyItemBuses 中）。
        itemRotation.end(it, maxTiles, dirtyItemBuses.size());

        for (Map.Entry<IGrid, List<TileMEAsyncItemOutputBus>> entry : gridMap.entrySet()) {
            processItemGrid(entry.getKey(), entry.getValue());
        }

        // 处理完后，把已经清空的 tile 从 dirty 集合移除。
        for (List<TileMEAsyncItemOutputBus> buses : gridMap.values()) {
            for (TileMEAsyncItemOutputBus bus : buses) {
                if (bus.getItemBuffer().isEmpty()) {
                    dirtyItemBuses.remove(bus);
                }
            }
        }
    }

    /**
     * 处理同一网格内的所有物品总线。
     * <p>
     * 对每个总线单独捕获异常，避免某个总线出错导致同网格的其他总线被跳过。
     */
    private void processItemGrid(IGrid grid, List<TileMEAsyncItemOutputBus> buses) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        IEnergySource energy = grid.getCache(IEnergyGrid.class);
        if (storage == null || energy == null) {
            return;
        }
        IMEMonitor<IAEItemStack> monitor = storage.getInventory(itemChannel);

        for (TileMEAsyncItemOutputBus bus : buses) {
            try {
                processItemBus(bus, monitor, energy);
            } catch (Exception e) {
                // 记录异常但继续处理同网格的其他总线；出错总线保留在 dirty 集合中稍后重试。
                MMCEAddition.LOGGER.error(
                        "Failed to process item output bus at {} (grid {}). Will retry next cycle.",
                        bus.getPos(), grid, e);
            }
        }
    }

    /**
     * 处理单个物品总线：把缓冲区中每种物品一次性注入 ME 网络。
     */
    private void processItemBus(TileMEAsyncItemOutputBus bus, IMEMonitor<IAEItemStack> monitor, IEnergySource energy) {
        LongItemBuffer buffer = bus.getItemBuffer();
        processItemBusItem(bus, buffer, monitor, energy, bus.getSource());
    }

    /**
     * 把某条物品缓冲区中每种物品一次性注入 ME 网络。
     *
     * @param owner 产出所属方块（非空），仅用于丢弃日志的定位与节流
     */
    private void processItemBusItem(TileEntity owner, LongItemBuffer buffer, IMEMonitor<IAEItemStack> monitor, IEnergySource energy, IActionSource source) {
        // 先取快照，避免在遍历过程中缓冲区被并发修改。
        Map<ItemVariant, Long> snapshot = buffer.snapshot();
        for (Map.Entry<ItemVariant, Long> entry : snapshot.entrySet()) {
            ItemVariant variant = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }

            // 创建代表该变体的 AE 物品堆，数量为 1，然后再 setStackSize。
            IAEItemStack toInsert = AEItemStack.fromItemStack(variant.toSingleStack());
            if (toInsert == null) {
                // 刻意丢弃：AE2 无法把该变体包装成网络资源，留在缓冲区只会永久堵塞这个方块，
                // 因此这里是本类唯一允许销毁产出的路径（流体路径同理），并记一条限流的 warn 日志。
                long dropped = buffer.extract(variant, amount);
                if (allowDiscardLog(owner)) {
                    MMCEAddition.LOGGER.warn(
                            "ME 输出丢弃无法表示的资源：物品 {} x{}（位置 {}），已从缓冲区移除 {}；同一方块每 {} 个注入周期最多记录一条",
                            variant.toSingleStack().getDisplayName(), amount, owner.getPos(), dropped,
                            DISCARD_LOG_INTERVAL_CYCLES);
                }
                continue;
            }
            toInsert.setStackSize(amount);

            // Platform.poweredInsert 会先扣除能量，然后把物品存入网络，返回剩余量。
            IAEItemStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                buffer.extract(variant, inserted);
                cycleInjectedItems += inserted;
                cycleInjectedOps++;
            } else if (MMCEAdditionConfig.debugPatternAssembly) {
                // 注入完全失败：网络没有对应存储空间、能量不足或安全权限拦截
                MMCEAddition.LOGGER.debug("ME output injection rejected: {} x{} (network full / no energy / security?)",
                        variant.toSingleStack().getDisplayName(), amount);
            }
        }
    }

    private void processPatternAssemblyOutputs() {
        if (dirtyPatternAssemblies.isEmpty()) {
            return;
        }

        Map<IGrid, List<TileMEPatternAssembly>> gridMap = new HashMap<>();
        int collected = 0;
        int maxTiles = getMaxTilesPerTick();

        Iterator<TileMEPatternAssembly> it = patternAssemblyRotation.begin(dirtyPatternAssemblies, maxTiles);
        while (it.hasNext() && (maxTiles <= 0 || collected < maxTiles)) {
            TileMEPatternAssembly assembly = it.next();
            if (!isValid(assembly)) {
                it.remove();
                patternAssemblies.remove(assembly);
                continue;
            }
            if (!assembly.hasAnyOutput()) {
                it.remove();
                continue;
            }
            try {
                IGrid grid = assembly.getProxy().getGrid();
                gridMap.computeIfAbsent(grid, k -> new ArrayList<>()).add(assembly);
                collected++;
            } catch (GridAccessException ignored) {
            }
        }
        // 轮转推进：被配额截断时下次从本轮处理到的位置之后继续（截断的 assembly 仍在 dirty 集合中）。
        patternAssemblyRotation.end(it, maxTiles, dirtyPatternAssemblies.size());

        for (Map.Entry<IGrid, List<TileMEPatternAssembly>> entry : gridMap.entrySet()) {
            processPatternAssemblyGrid(entry.getKey(), entry.getValue());
        }

        for (List<TileMEPatternAssembly> assemblies : gridMap.values()) {
            for (TileMEPatternAssembly assembly : assemblies) {
                if (!assembly.hasAnyOutput()) {
                    dirtyPatternAssemblies.remove(assembly);
                }
            }
        }
    }

    private void processPatternAssemblyGrid(IGrid grid, List<TileMEPatternAssembly> assemblies) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        IEnergySource energy = grid.getCache(IEnergyGrid.class);
        if (storage == null || energy == null) {
            return;
        }
        IMEMonitor<IAEItemStack> itemMonitor = storage.getInventory(itemChannel);
        IMEMonitor<IAEFluidStack> fluidMonitor = storage.getInventory(fluidChannel);

        for (TileMEPatternAssembly assembly : assemblies) {
            try {
                for (PatternAssemblySlot slot : assembly.getSlots()) {
                    processItemBusItem(assembly, slot.getOutputItemBuffer(), itemMonitor, energy, assembly.getSource());
                    processFluidHatchFluid(assembly, slot.getOutputFluidBuffer(), fluidMonitor, energy, assembly.getSource());
                }
            } catch (Exception e) {
                MMCEAddition.LOGGER.error(
                        "Failed to process pattern assembly output at {} (grid {}). Will retry next cycle.",
                        assembly.getPos(), grid, e);
            }
        }
    }

    /**
     * 处理所有待处理的 ME 输出总成仓。
     * <p>
     * 逻辑与样板总成对称：同一仓的物品与流体缓冲共用一次网格查询。
     */
    private void processOutputAssemblyOutputs() {
        if (dirtyOutputAssemblies.isEmpty()) {
            return;
        }

        Map<IGrid, List<TileMEOutputAssembly>> gridMap = new HashMap<>();
        int collected = 0;
        int maxTiles = getMaxTilesPerTick();

        Iterator<TileMEOutputAssembly> it = outputAssemblyRotation.begin(dirtyOutputAssemblies, maxTiles);
        while (it.hasNext() && (maxTiles <= 0 || collected < maxTiles)) {
            TileMEOutputAssembly assembly = it.next();
            if (!isValid(assembly)) {
                it.remove();
                outputAssemblies.remove(assembly);
                continue;
            }
            if (!assembly.hasAnyOutput()) {
                it.remove();
                continue;
            }
            try {
                IGrid grid = assembly.getProxy().getGrid();
                gridMap.computeIfAbsent(grid, k -> new ArrayList<>()).add(assembly);
                collected++;
            } catch (GridAccessException ignored) {
            }
        }
        // 轮转推进：被配额截断时下次从本轮处理到的位置之后继续（截断的 assembly 仍在 dirty 集合中）。
        outputAssemblyRotation.end(it, maxTiles, dirtyOutputAssemblies.size());

        for (Map.Entry<IGrid, List<TileMEOutputAssembly>> entry : gridMap.entrySet()) {
            processOutputAssemblyGrid(entry.getKey(), entry.getValue());
        }

        for (List<TileMEOutputAssembly> assemblies : gridMap.values()) {
            for (TileMEOutputAssembly assembly : assemblies) {
                if (!assembly.hasAnyOutput()) {
                    dirtyOutputAssemblies.remove(assembly);
                }
            }
        }
    }

    private void processOutputAssemblyGrid(IGrid grid, List<TileMEOutputAssembly> assemblies) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        IEnergySource energy = grid.getCache(IEnergyGrid.class);
        if (storage == null || energy == null) {
            return;
        }
        IMEMonitor<IAEItemStack> itemMonitor = storage.getInventory(itemChannel);
        IMEMonitor<IAEFluidStack> fluidMonitor = storage.getInventory(fluidChannel);

        for (TileMEOutputAssembly assembly : assemblies) {
            try {
                processItemBusItem(assembly, assembly.getItemBuffer(), itemMonitor, energy, assembly.getSource());
                processFluidHatchFluid(assembly, assembly.getFluidBuffer(), fluidMonitor, energy, assembly.getSource());
            } catch (Exception e) {
                MMCEAddition.LOGGER.error(
                        "Failed to process ME output assembly at {} (grid {}). Will retry next cycle.",
                        assembly.getPos(), grid, e);
            }
        }
    }

    /**
     * 处理所有待处理的流体仓。
     * <p>
     * 逻辑与物品版对称。
     */
    private void processFluidOutputs() {
        if (dirtyFluidHatches.isEmpty()) {
            return;
        }

        Map<IGrid, List<TileMEAsyncFluidOutputHatch>> gridMap = new HashMap<>();
        int collected = 0;
        int maxTiles = getMaxTilesPerTick();

        Iterator<TileMEAsyncFluidOutputHatch> it = fluidRotation.begin(dirtyFluidHatches, maxTiles);
        while (it.hasNext() && (maxTiles <= 0 || collected < maxTiles)) {
            TileMEAsyncFluidOutputHatch hatch = it.next();
            if (!isValid(hatch)) {
                it.remove();
                fluidHatches.remove(hatch);
                continue;
            }
            if (hatch.getFluidBuffer().isEmpty()) {
                it.remove();
                continue;
            }
            try {
                IGrid grid = hatch.getProxy().getGrid();
                gridMap.computeIfAbsent(grid, k -> new ArrayList<>()).add(hatch);
                collected++;
            } catch (GridAccessException ignored) {
            }
        }
        // 轮转推进：被配额截断时下次从本轮处理到的位置之后继续（截断的 hatch 仍在 dirty 集合中）。
        fluidRotation.end(it, maxTiles, dirtyFluidHatches.size());

        for (Map.Entry<IGrid, List<TileMEAsyncFluidOutputHatch>> entry : gridMap.entrySet()) {
            processFluidGrid(entry.getKey(), entry.getValue());
        }

        for (List<TileMEAsyncFluidOutputHatch> hatches : gridMap.values()) {
            for (TileMEAsyncFluidOutputHatch hatch : hatches) {
                if (hatch.getFluidBuffer().isEmpty()) {
                    dirtyFluidHatches.remove(hatch);
                }
            }
        }
    }

    private void processFluidGrid(IGrid grid, List<TileMEAsyncFluidOutputHatch> hatches) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        IEnergySource energy = grid.getCache(IEnergyGrid.class);
        if (storage == null || energy == null) {
            return;
        }
        IMEMonitor<IAEFluidStack> monitor = storage.getInventory(fluidChannel);

        for (TileMEAsyncFluidOutputHatch hatch : hatches) {
            try {
                processFluidHatch(hatch, monitor, energy);
            } catch (Exception e) {
                MMCEAddition.LOGGER.error(
                        "Failed to process fluid output hatch at {} (grid {}). Will retry next cycle.",
                        hatch.getPos(), grid, e);
            }
        }
    }

    private void processFluidHatch(TileMEAsyncFluidOutputHatch hatch, IMEMonitor<IAEFluidStack> monitor, IEnergySource energy) {
        LongFluidBuffer buffer = hatch.getFluidBuffer();
        processFluidHatchFluid(hatch, buffer, monitor, energy, hatch.getSource());
    }

    /**
     * 把某条流体缓冲区中的每种流体一次性注入 ME 网络。
     *
     * @param owner 产出所属方块（非空），仅用于丢弃日志的定位与节流
     */
    private void processFluidHatchFluid(TileEntity owner, LongFluidBuffer buffer, IMEMonitor<IAEFluidStack> monitor, IEnergySource energy, IActionSource source) {
        Map<Fluid, Long> snapshot = buffer.snapshot();
        for (Map.Entry<Fluid, Long> entry : snapshot.entrySet()) {
            Fluid fluid = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }

            IAEFluidStack toInsert = AEFluidStack.fromFluidStack(new FluidStack(fluid, 1));
            if (toInsert == null) {
                // 刻意丢弃：AE2 无法把该流体包装成网络资源，留在缓冲区只会永久堵塞这个方块，
                // 因此这里是本类唯一允许销毁产出的路径（物品路径同理），并记一条限流的 warn 日志。
                long dropped = buffer.extract(fluid, amount);
                if (allowDiscardLog(owner)) {
                    MMCEAddition.LOGGER.warn(
                            "ME 输出丢弃无法表示的资源：流体 {} x{} mB（位置 {}），已从缓冲区移除 {}；同一方块每 {} 个注入周期最多记录一条",
                            fluid.getName(), amount, owner.getPos(), dropped, DISCARD_LOG_INTERVAL_CYCLES);
                }
                continue;
            }
            toInsert.setStackSize(amount);

            IAEFluidStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                buffer.extract(fluid, inserted);
                cycleInjectedFluids += inserted;
                cycleInjectedOps++;
            } else if (MMCEAdditionConfig.debugPatternAssembly) {
                // 注入完全失败：网络没有流体存储、能量不足或安全权限拦截
                MMCEAddition.LOGGER.debug("ME fluid output injection rejected: {} x{} mB (no fluid storage / no energy / security?)",
                        fluid.getName(), amount);
            }
        }
    }

    /**
     * 丢弃日志节流：同一个方块在 {@link #DISCARD_LOG_INTERVAL_CYCLES} 个注入周期内最多记一条。
     * <p>
     * 只在产出无法表示为网络资源（{@code toInsert == null}）这条冷路径上调用。
     *
     * @param owner 产出所属方块，作为节流键（弱引用，不会阻止方块被回收）
     * @return true 表示本次允许记录日志
     */
    private boolean allowDiscardLog(Object owner) {
        long now = statTotalCycles;
        Long last = discardLogCycle.get(owner);
        if (last != null && now - last < DISCARD_LOG_INTERVAL_CYCLES) {
            return false;
        }
        discardLogCycle.put(owner, now);
        return true;
    }

    /**
     * 简化有效性检查。
     * <p>
     * 依赖 TileEntity 的 invalidate/onChunkUnload 正常注销。
     * 不再每 tick 调用 world.getTileEntity(pos) 做二次校验，以减少开销。
     */
    private boolean isValid(TileEntity tile) {
        return tile != null && !tile.isInvalid();
    }

    /**
     * 获取当前配置允许的“单类仓室每注入周期”最大处理数量。
     * <p>
     * 配额语义：物品总线、流体仓、ME 样板总成、ME 输出总成仓四类各自独立持有这份预算，
     * 因此一个注入周期内合计最多处理 4 倍于此值的方块；注入周期是
     * {@code injectionInterval} 个 server tick，而不是每个 tick 都执行一次。
     * <p>
     * 取值为 0 表示不限制。Forge 的 {@code @RangeInt(min = 0, ...)} 已保证配置值非负，
     * 这里再防御性钳制一次（只有手改配置文件才可能出现负值，按“不限制”处理）。
     *
     * @return 单类仓室每注入周期的最大处理数，0 表示无限制
     */
    private int getMaxTilesPerTick() {
        return Math.max(0, MMCEAdditionConfig.maxTilesPerTick);
    }

    /**
     * 单类仓室的限流轮转状态：让被 {@code max_tiles_per_tick} 截断的仓室在后续周期里轮流补上。
     * <p>
     * 语义：{@link #phase} 表示“本周期从脏集合迭代序的第 phase 个元素之后开始收集”。
     * 只有上一周期确实因为配额被截断（{@link #truncated}）时才跳跃；跳跃只是空转迭代，
     * 不删除任何元素，因此被截断的方块仍留在脏集合里等待下个周期。若某周期配额没用完
     * 就迭代到了集合末尾，相位归零，下一周期重新从集合开头开始（常规情况因此零额外开销）。
     * <p>
     * 公平性：设脏集合大小 S、配额 M，被截断的周期每次把相位推进 M（对 S 取模），
     * 迭代器从该位置一路收集到集合末尾，因此任意元素最多在 ⌈S/M⌉ + 1 个周期内被扫到一次，
     * 不存在“排在集合尾部就永远轮不到”的饿死。集合是弱一致迭代且期间可能增删，位置会有漂移，
     * 所以这是启发式而非严格上界；但相位只前进、不会被固定在集合头部，永久饿死不可能发生。
     * <p>
     * 只在服务端主线程的注入周期（ServerTickEvent.End）内访问，不需要同步与 volatile。
     */
    private static final class Rotation {

        /** 本周期要跳过的迭代位置数量（以集合迭代序的元素个数计）。 */
        private int phase;

        /** 上一周期是否因为配额被截断；未被截断时下一周期无需跳跃。 */
        private boolean truncated;

        /**
         * 开始一周期的收集，返回已跳跃到轮转起点的迭代器。
         *
         * @param set      本类的脏集合（弱一致迭代，允许主线程之外的 markDirty 写入）
         * @param maxTiles 本类本周期配额，&lt;= 0 表示不限制（此时不可能被截断）
         */
        <T> Iterator<T> begin(Set<T> set, int maxTiles) {
            Iterator<T> it = set.iterator();
            if (!truncated || maxTiles <= 0) {
                // 上一周期没被截断，或本周期不限制：从集合开头开始，零额外开销。
                phase = 0;
                return it;
            }
            int size = set.size();
            if (size <= 1) {
                phase = 0;
                truncated = false;
                return it;
            }
            // 集合大小可能已经变化（相位是按上一周期的旧大小取的模），这里再取一次模保证落在范围内。
            for (int i = 0, skip = phase % size; i < skip && it.hasNext(); i++) {
                it.next();
            }
            return it;
        }

        /**
         * 结束一周期的收集，按是否被截断推进相位。
         *
         * @param it       本周期使用的迭代器（循环结束时 {@code hasNext()} 为 true 即表示被配额截断）
         * @param maxTiles 本周期配额，&lt;= 0 表示不限制
         * @param size     本周期结束时的脏集合大小
         */
        void end(Iterator<?> it, int maxTiles, int size) {
            if (maxTiles <= 0 || !it.hasNext()) {
                // 配额没用完就迭代到末尾：本周期已扫过相位之后的全部元素，下周期从集合开头重新开始。
                phase = 0;
                truncated = false;
                return;
            }
            truncated = true;
            phase = (phase + maxTiles) % Math.max(1, size);
        }
    }
}

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
 */
public enum MEAsyncOutputManager {
    INSTANCE;

    /**
     * 默认的每 tick 最多处理方块数上限。
     * <p>
     * 实际值由配置 {@link MMCEAdditionConfig#maxTilesPerTick} 决定；
     * 配置为 0 时表示不限制。
     */
    private static final int DEFAULT_MAX_TILES_PER_TICK = 2000;

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
        processItemOutputs();
        processFluidOutputs();
        processPatternAssemblyOutputs();
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

        Iterator<TileMEAsyncItemOutputBus> it = dirtyItemBuses.iterator();
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
        processItemBusItem(buffer, monitor, energy, bus.getSource());
    }

    private void processItemBusItem(LongItemBuffer buffer, IMEMonitor<IAEItemStack> monitor, IEnergySource energy, IActionSource source) {
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
                // 如果无法创建 AEItemStack，直接丢弃（极端情况）。
                buffer.extract(variant, amount);
                continue;
            }
            toInsert.setStackSize(amount);

            // Platform.poweredInsert 会先扣除能量，然后把物品存入网络，返回剩余量。
            IAEItemStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                buffer.extract(variant, inserted);
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

        Iterator<TileMEPatternAssembly> it = dirtyPatternAssemblies.iterator();
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
                    processItemBusItem(slot.getOutputItemBuffer(), itemMonitor, energy, assembly.getSource());
                    processFluidHatchFluid(slot.getOutputFluidBuffer(), fluidMonitor, energy, assembly.getSource());
                }
            } catch (Exception e) {
                MMCEAddition.LOGGER.error(
                        "Failed to process pattern assembly output at {} (grid {}). Will retry next cycle.",
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

        Iterator<TileMEAsyncFluidOutputHatch> it = dirtyFluidHatches.iterator();
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
        processFluidHatchFluid(buffer, monitor, energy, hatch.getSource());
    }

    private void processFluidHatchFluid(LongFluidBuffer buffer, IMEMonitor<IAEFluidStack> monitor, IEnergySource energy, IActionSource source) {
        Map<Fluid, Long> snapshot = buffer.snapshot();
        for (Map.Entry<Fluid, Long> entry : snapshot.entrySet()) {
            Fluid fluid = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }

            IAEFluidStack toInsert = AEFluidStack.fromFluidStack(new FluidStack(fluid, 1));
            if (toInsert == null) {
                buffer.extract(fluid, amount);
                continue;
            }
            toInsert.setStackSize(amount);

            IAEFluidStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                buffer.extract(fluid, inserted);
            } else if (MMCEAdditionConfig.debugPatternAssembly) {
                // 注入完全失败：网络没有流体存储、能量不足或安全权限拦截
                MMCEAddition.LOGGER.debug("ME fluid output injection rejected: {} x{} mB (no fluid storage / no energy / security?)",
                        fluid.getName(), amount);
            }
        }
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
     * 获取当前配置允许的单批最大处理数量。
     * <p>
     * 配置为 0 时表示不限制；小于 0 时按默认值处理。
     *
     * @return 单批最大 tile 数，0 表示无限制
     */
    private int getMaxTilesPerTick() {
        int configured = MMCEAdditionConfig.maxTilesPerTick;
        if (configured < 0) {
            return DEFAULT_MAX_TILES_PER_TICK;
        }
        return configured;
    }
}

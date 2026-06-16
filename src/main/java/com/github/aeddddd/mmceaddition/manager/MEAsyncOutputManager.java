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
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncFluidOutputHatch;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
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
     * 每 tick 最多处理的方块数上限。
     * <p>
     * 即使有大量脏方块，也分批处理，避免单次 tick 耗时过长。
     */
    private static final int MAX_TILES_PER_TICK = 500;

    /**
     * 所有已注册的异步物品总线。
     */
    private final Set<TileMEAsyncItemOutputBus> itemBuses = ConcurrentHashMap.newKeySet();

    /**
     * 所有已注册的异步流体仓。
     */
    private final Set<TileMEAsyncFluidOutputHatch> fluidHatches = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的物品总线：只有缓冲区非空的 tile 才会在这里。
     */
    private final Set<TileMEAsyncItemOutputBus> dirtyItemBuses = ConcurrentHashMap.newKeySet();

    /**
     * 待处理的流体仓。
     */
    private final Set<TileMEAsyncFluidOutputHatch> dirtyFluidHatches = ConcurrentHashMap.newKeySet();

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
        int interval = Math.max(1, MMCEAdditionConfig.injectionInterval);
        if (tickCounter % interval != 0) {
            return;
        }
        processItemOutputs();
        processFluidOutputs();
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

        Iterator<TileMEAsyncItemOutputBus> it = dirtyItemBuses.iterator();
        while (it.hasNext() && collected < MAX_TILES_PER_TICK) {
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
     */
    private void processItemGrid(IGrid grid, List<TileMEAsyncItemOutputBus> buses) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        IEnergySource energy = grid.getCache(IEnergyGrid.class);
        if (storage == null || energy == null) {
            return;
        }
        IMEMonitor<IAEItemStack> monitor = storage.getInventory(itemChannel);

        for (TileMEAsyncItemOutputBus bus : buses) {
            processItemBus(bus, monitor, energy);
        }
    }

    /**
     * 处理单个物品总线：把缓冲区中每种物品一次性注入 ME 网络。
     */
    private void processItemBus(TileMEAsyncItemOutputBus bus, IMEMonitor<IAEItemStack> monitor, IEnergySource energy) {
        LongItemBuffer buffer = bus.getItemBuffer();
        IActionSource source = bus.getSource();

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

        Iterator<TileMEAsyncFluidOutputHatch> it = dirtyFluidHatches.iterator();
        while (it.hasNext() && collected < MAX_TILES_PER_TICK) {
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
            processFluidHatch(hatch, monitor, energy);
        }
    }

    private void processFluidHatch(TileMEAsyncFluidOutputHatch hatch, IMEMonitor<IAEFluidStack> monitor, IEnergySource energy) {
        LongFluidBuffer buffer = hatch.getFluidBuffer();
        IActionSource source = hatch.getSource();

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
}

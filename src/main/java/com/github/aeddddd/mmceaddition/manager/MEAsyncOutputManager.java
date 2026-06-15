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
 * 统一管理所有 ME 异步输出方块，按 AE 网格分组批量注入，避免每个方块都注册为 IGridTickable。
 * <p>
 * 性能优化：只处理缓冲区非空（dirty）的方块，而不是每 tick 扫描全部注册方块。
 */
public enum MEAsyncOutputManager {
    INSTANCE;

    private static final int MAX_TILES_PER_TICK = 500;

    private final Set<TileMEAsyncItemOutputBus> itemBuses = ConcurrentHashMap.newKeySet();
    private final Set<TileMEAsyncFluidOutputHatch> fluidHatches = ConcurrentHashMap.newKeySet();

    /**
     * 待处理集合：只有缓冲区非空的 tile 才会进入这里。
     */
    private final Set<TileMEAsyncItemOutputBus> dirtyItemBuses = ConcurrentHashMap.newKeySet();
    private final Set<TileMEAsyncFluidOutputHatch> dirtyFluidHatches = ConcurrentHashMap.newKeySet();

    private final IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    private final IFluidStorageChannel fluidChannel = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

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
     * 当缓冲区从空变为非空时由 Tile 调用。
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

    private void processItemOutputs() {
        if (dirtyItemBuses.isEmpty()) {
            return;
        }

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
                // 尚未连接到网格，保留在 dirty 集合中等待下次尝试
            }
        }

        for (Map.Entry<IGrid, List<TileMEAsyncItemOutputBus>> entry : gridMap.entrySet()) {
            processItemGrid(entry.getKey(), entry.getValue());
        }

        // 处理完后清空已处理且缓冲区为空的 tile
        for (List<TileMEAsyncItemOutputBus> buses : gridMap.values()) {
            for (TileMEAsyncItemOutputBus bus : buses) {
                if (bus.getItemBuffer().isEmpty()) {
                    dirtyItemBuses.remove(bus);
                }
            }
        }
    }

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

    private void processItemBus(TileMEAsyncItemOutputBus bus, IMEMonitor<IAEItemStack> monitor, IEnergySource energy) {
        LongItemBuffer buffer = bus.getItemBuffer();
        IActionSource source = bus.getSource();

        Map<ItemVariant, Long> snapshot = buffer.snapshot();
        for (Map.Entry<ItemVariant, Long> entry : snapshot.entrySet()) {
            ItemVariant variant = entry.getKey();
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }

            IAEItemStack toInsert = AEItemStack.fromItemStack(variant.toSingleStack());
            if (toInsert == null) {
                buffer.extract(variant, amount);
                continue;
            }
            toInsert.setStackSize(amount);

            IAEItemStack leftover = Platform.poweredInsert(energy, monitor, toInsert, source);
            long inserted = amount - (leftover == null ? 0 : leftover.getStackSize());
            if (inserted > 0) {
                buffer.extract(variant, inserted);
            }
        }
    }

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
     * 简化有效性检查：依赖 invalidate/onChunkUnload 正常注销。
     * 不再每 tick 调用 world.getTileEntity(pos) 做二次校验。
     */
    private boolean isValid(TileEntity tile) {
        return tile != null && !tile.isInvalid();
    }
}

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
 */
public enum MEAsyncOutputManager {
    INSTANCE;

    private static final int MAX_TILES_PER_TICK = 500;

    private final Set<TileMEAsyncItemOutputBus> itemBuses = ConcurrentHashMap.newKeySet();
    private final Set<TileMEAsyncFluidOutputHatch> fluidHatches = ConcurrentHashMap.newKeySet();

    private final IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    private final IFluidStorageChannel fluidChannel = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

    private int itemCursor = 0;
    private int fluidCursor = 0;

    public void register(TileMEAsyncItemOutputBus bus) {
        itemBuses.add(bus);
    }

    public void unregister(TileMEAsyncItemOutputBus bus) {
        itemBuses.remove(bus);
    }

    public void register(TileMEAsyncFluidOutputHatch hatch) {
        fluidHatches.add(hatch);
    }

    public void unregister(TileMEAsyncFluidOutputHatch hatch) {
        fluidHatches.remove(hatch);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        processItemOutputs();
        processFluidOutputs();
    }

    private void processItemOutputs() {
        List<TileMEAsyncItemOutputBus> snapshot = getValidItemBuses();
        if (snapshot.isEmpty()) {
            return;
        }

        int total = snapshot.size();
        int limit = Math.min(MAX_TILES_PER_TICK, total);
        int start = itemCursor % total;

        Map<IGrid, List<TileMEAsyncItemOutputBus>> gridMap = new HashMap<>();
        int collected = 0;
        for (int i = 0; i < total && collected < limit; i++) {
            int index = (start + i) % total;
            TileMEAsyncItemOutputBus bus = snapshot.get(index);
            if (bus.getItemBuffer().isEmpty()) {
                continue;
            }
            try {
                IGrid grid = bus.getProxy().getGrid();
                gridMap.computeIfAbsent(grid, k -> new ArrayList<>()).add(bus);
                collected++;
            } catch (GridAccessException ignored) {
            }
        }
        itemCursor = (start + collected) % Math.max(total, 1);

        for (Map.Entry<IGrid, List<TileMEAsyncItemOutputBus>> entry : gridMap.entrySet()) {
            processItemGrid(entry.getKey(), entry.getValue());
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
        List<TileMEAsyncFluidOutputHatch> snapshot = getValidFluidHatches();
        if (snapshot.isEmpty()) {
            return;
        }

        int total = snapshot.size();
        int limit = Math.min(MAX_TILES_PER_TICK, total);
        int start = fluidCursor % total;

        Map<IGrid, List<TileMEAsyncFluidOutputHatch>> gridMap = new HashMap<>();
        int collected = 0;
        for (int i = 0; i < total && collected < limit; i++) {
            int index = (start + i) % total;
            TileMEAsyncFluidOutputHatch hatch = snapshot.get(index);
            if (hatch.getFluidBuffer().isEmpty()) {
                continue;
            }
            try {
                IGrid grid = hatch.getProxy().getGrid();
                gridMap.computeIfAbsent(grid, k -> new ArrayList<>()).add(hatch);
                collected++;
            } catch (GridAccessException ignored) {
            }
        }
        fluidCursor = (start + collected) % Math.max(total, 1);

        for (Map.Entry<IGrid, List<TileMEAsyncFluidOutputHatch>> entry : gridMap.entrySet()) {
            processFluidGrid(entry.getKey(), entry.getValue());
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

    private List<TileMEAsyncItemOutputBus> getValidItemBuses() {
        List<TileMEAsyncItemOutputBus> list = new ArrayList<>();
        Iterator<TileMEAsyncItemOutputBus> it = itemBuses.iterator();
        while (it.hasNext()) {
            TileMEAsyncItemOutputBus bus = it.next();
            if (isValid(bus)) {
                list.add(bus);
            } else {
                it.remove();
            }
        }
        return list;
    }

    private List<TileMEAsyncFluidOutputHatch> getValidFluidHatches() {
        List<TileMEAsyncFluidOutputHatch> list = new ArrayList<>();
        Iterator<TileMEAsyncFluidOutputHatch> it = fluidHatches.iterator();
        while (it.hasNext()) {
            TileMEAsyncFluidOutputHatch hatch = it.next();
            if (isValid(hatch)) {
                list.add(hatch);
            } else {
                it.remove();
            }
        }
        return list;
    }

    private boolean isValid(TileEntity tile) {
        if (tile == null || tile.isInvalid()) {
            return false;
        }
        World world = tile.getWorld();
        BlockPos pos = tile.getPos();
        return world != null && world.isBlockLoaded(pos) && world.getTileEntity(pos) == tile;
    }
}

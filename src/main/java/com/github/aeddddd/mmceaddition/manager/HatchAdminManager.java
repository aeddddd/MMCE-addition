package com.github.aeddddd.mmceaddition.manager;

import ae2.api.AECapabilities;
import com.github.aeddddd.mmceaddition.tile.TileInputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncFluidOutputHatch;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
import com.github.aeddddd.mmceaddition.tile.TileMEOutputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.tile.slot.PatternAssemblySlot;
import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileItemInputBus;
import hellfirepvp.modularmachinery.common.tiles.TileItemOutputBus;
import hellfirepvp.modularmachinery.common.tiles.base.TileInventory;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 全局仓室管理器（服务端数据源）。
 * <p>
 * 遍历当前维度已加载区块的 TileEntity，按 {@link HatchType} 分类，
 * 汇总每个仓室的自动 IO 开关、AE 网络状态、缓冲存量与 tick 平均耗时，
 * 供仓室管理工具 GUI 展示；同时提供批量 IO 切换与缓冲清空操作。
 * <p>
 * 所有方法都必须在服务端主线程调用（由网络包处理器调度保证）。
 */
public final class HatchAdminManager {

    private HatchAdminManager() {
    }

    /**
     * MMCE 流体仓类的软引用。
     * <p>
     * TileFluidTank 实现的 Mekanism 气体 API 不在编译 classpath 上，
     * 直接 instanceof 会导致编译失败；MMCE 是必需依赖，运行时 Class.forName 必然成功。
     */
    private static final Class<?> CLAZZ_FLUID_INPUT =
            forName("hellfirepvp.modularmachinery.common.tiles.TileFluidInputHatch");
    private static final Class<?> CLAZZ_FLUID_OUTPUT =
            forName("hellfirepvp.modularmachinery.common.tiles.TileFluidOutputHatch");
    /** 能源仓同理：TileEnergyHatch 实现的 CoFH/mcjty API 不在编译 classpath 上。 */
    private static final Class<?> CLAZZ_ENERGY_INPUT =
            forName("hellfirepvp.modularmachinery.common.tiles.TileEnergyInputHatch");
    private static final Class<?> CLAZZ_ENERGY_OUTPUT =
            forName("hellfirepvp.modularmachinery.common.tiles.TileEnergyOutputHatch");

    private static Class<?> forName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** 单次同步最多发送的条目数，避免巨型存档下网络包过大。 */
    public static final int MAX_ENTRIES = 512;

    /** 网络过滤：全部。 */
    public static final int NET_ALL = 0;
    /** 网络过滤：仅在线。 */
    public static final int NET_ONLINE = 1;
    /** 网络过滤：仅离线。 */
    public static final int NET_OFFLINE = 2;

    /** 排序：按类型。 */
    public static final int SORT_TYPE = 0;
    /** 排序：按缓存量降序。 */
    public static final int SORT_BUFFERED = 1;
    /** 排序：按 tick 平均耗时降序。 */
    public static final int SORT_TICK_TIME = 2;

    /**
     * 一个仓室条目的快照数据（全部为基本类型，可直接网络序列化）。
     */
    public static final class Entry {
        public BlockPos pos;
        public int dim;
        public HatchType type;
        /** 自动 IO 状态：-1 不支持，0 关闭，1 开启。 */
        public byte autoIO;
        /** AE 网络状态：-1 非网络仓室，0 离线，1 在线。 */
        public byte network;
        /** 缓存量（物品个 / 流体 mB / 能源 RF；-1 表示无缓存概念）。 */
        public long buffered;
        /** 平均每 tick 耗时（纳秒；采样关闭或无数据时为 0）。 */
        public long avgNanos;
        /** 显示名（服务端解析，客户端直接展示）。 */
        public String name = "";
        /** 图标物品注册名 + meta（客户端渲染行首图标）。 */
        public String icon = "";
        public int meta;
    }

    /**
     * 扫描结果：条目列表 + 过滤后总数（条目数可能被 {@link #MAX_ENTRIES} 截断）
     * + 类型掩码范围内 AE 仓室的在线/离线统计（不受网络过滤影响）。
     */
    public static final class ScanResult {
        public final List<Entry> entries;
        public final int totalFound;
        /** 类型掩码范围内、带 AE 网络的仓室中当前在线数。 */
        public int onlineCount;
        /** 同上，离线数。 */
        public int offlineCount;

        ScanResult(List<Entry> entries, int totalFound) {
            this.entries = entries;
            this.totalFound = totalFound;
        }
    }

    /**
     * 扫描世界中所有已加载区块的仓室。
     *
     * @param world     服务端世界（通常取发送请求的玩家所在维度）
     * @param typeMask  类型筛选掩码（{@link HatchType#bit()} 组合，{@link HatchType#ALL_MASK} 为全部）
     * @param netFilter 网络过滤（{@link #NET_ALL}/{@link #NET_ONLINE}/{@link #NET_OFFLINE}）
     * @param sortBy    排序方式（{@link #SORT_TYPE}/{@link #SORT_BUFFERED}/{@link #SORT_TICK_TIME}）
     */
    public static ScanResult scan(World world, int typeMask, int netFilter, int sortBy) {
        List<Entry> entries = new ArrayList<>();
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            return new ScanResult(entries, 0);
        }
        HatchProfiler.INSTANCE.cleanInvalid();
        ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
        int dim = world.provider.getDimension();
        int onlineCount = 0;
        int offlineCount = 0;
        for (Chunk chunk : provider.getLoadedChunks()) {
            for (TileEntity te : chunk.getTileEntityMap().values()) {
                HatchType type = classify(te);
                if (type == null || (typeMask & type.bit()) == 0) {
                    continue;
                }
                byte net = networkState(te);
                // 在线/离线统计不受网络过滤影响，反映类型范围内的整体网络状况
                if (net == 1) {
                    onlineCount++;
                } else if (net == 0) {
                    offlineCount++;
                }
                if (netFilter == NET_ONLINE && net != 1) {
                    continue;
                }
                if (netFilter == NET_OFFLINE && net != 0) {
                    continue;
                }
                entries.add(buildEntry(world, te, dim, type, net));
            }
        }
        sort(entries, sortBy);
        int total = entries.size();
        if (total > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        ScanResult result = new ScanResult(entries, total);
        result.onlineCount = onlineCount;
        result.offlineCount = offlineCount;
        return result;
    }

    /**
     * 批量切换自动 IO。返回实际切换的仓室数量。
     *
     * @param typeMask 作用的类型掩码（只影响支持自动 IO 的仓室）
     * @param enable   目标状态
     */
    public static int applyAutoIO(World world, int typeMask, boolean enable) {
        int changed = 0;
        for (TileEntity te : collectMatching(world, typeMask)) {
            if (te instanceof IAutoIOControllable) {
                ((IAutoIOControllable) te).mmceaddition$setAutoIOEnabled(enable);
                te.markDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 批量清空缓存（本模组 Long 缓冲、MMCE 物品/流体/能源仓内容）。返回清空的仓室数量。
     */
    public static int clearBuffers(World world, int typeMask) {
        int cleared = 0;
        for (TileEntity te : collectMatching(world, typeMask)) {
            if (clearBuffer(te)) {
                te.markDirty();
                cleared++;
            }
        }
        return cleared;
    }

    /** 收集世界中所有匹配类型掩码的仓室 TileEntity。 */
    private static List<TileEntity> collectMatching(World world, int typeMask) {
        List<TileEntity> out = new ArrayList<>();
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            return out;
        }
        ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
        for (Chunk chunk : provider.getLoadedChunks()) {
            for (TileEntity te : chunk.getTileEntityMap().values()) {
                HatchType type = classify(te);
                if (type != null && (typeMask & type.bit()) != 0) {
                    out.add(te);
                }
            }
        }
        return out;
    }

    /** 仓室分类。注意判断顺序：本模组与 ME 总线先于 MMCE 基类，控制器最后兜底。 */
    @Nullable
    private static HatchType classify(TileEntity te) {
        if (te instanceof TileMEAsyncItemOutputBus) return HatchType.ASYNC_ITEM_OUT;
        if (te instanceof TileMEAsyncFluidOutputHatch) return HatchType.ASYNC_FLUID_OUT;
        if (te instanceof TileMEPatternAssembly) return HatchType.PATTERN_ASSEMBLY;
        if (te instanceof TileMEOutputAssembly) return HatchType.OUTPUT_ASSEMBLY;
        if (te instanceof TileInputAssembly) return HatchType.INPUT_ASSEMBLY;
        // Identify optional MMCE devices without resolving their legacy appeng superclasses.
        String block = String.valueOf(te.getBlockType().getRegistryName());
        switch (block) {
            case "modularmachinery:blockmeiteminputbus": return HatchType.ME_ITEM_IN;
            case "modularmachinery:blockmeitemoutputbus": return HatchType.ME_ITEM_OUT;
            case "modularmachinery:blockmefluidinputbus": return HatchType.ME_FLUID_IN;
            case "modularmachinery:blockmefluidoutputbus": return HatchType.ME_FLUID_OUT;
            case "modularmachinery:blockmepatternprovider": return HatchType.ME_PATTERN_PROVIDER;
        }
        if (te instanceof TileItemInputBus) return HatchType.ITEM_IN;
        if (te instanceof TileItemOutputBus) return HatchType.ITEM_OUT;
        if (CLAZZ_FLUID_INPUT != null && CLAZZ_FLUID_INPUT.isInstance(te)) return HatchType.FLUID_IN;
        if (CLAZZ_FLUID_OUTPUT != null && CLAZZ_FLUID_OUTPUT.isInstance(te)) return HatchType.FLUID_OUT;
        if (CLAZZ_ENERGY_INPUT != null && CLAZZ_ENERGY_INPUT.isInstance(te)) return HatchType.ENERGY_IN;
        if (CLAZZ_ENERGY_OUTPUT != null && CLAZZ_ENERGY_OUTPUT.isInstance(te)) return HatchType.ENERGY_OUT;
        if (te instanceof TileMultiblockMachineController) return HatchType.CONTROLLER;
        return null;
    }

    /** 构建单个条目快照。 */
    private static Entry buildEntry(World world, TileEntity te, int dim, HatchType type, byte net) {
        Entry entry = new Entry();
        entry.pos = te.getPos();
        entry.dim = dim;
        entry.type = type;
        entry.network = net;
        entry.autoIO = te instanceof IAutoIOControllable
                ? (byte) (((IAutoIOControllable) te).mmceaddition$isAutoIOEnabled() ? 1 : 0)
                : (byte) -1;
        entry.buffered = bufferedAmount(te);
        entry.avgNanos = HatchProfiler.INSTANCE.avgNanos(te);
        IBlockState state = world.getBlockState(entry.pos);
        entry.icon = String.valueOf(state.getBlock().getRegistryName());
        entry.meta = state.getBlock().getMetaFromState(state);
        try {
            entry.name = new ItemStack(state.getBlock(), 1, entry.meta).getDisplayName();
        } catch (Exception ignored) {
            entry.name = entry.icon;
        }
        return entry;
    }

    /** AE 网络状态：1 在线，0 离线/无频道，-1 非网络仓室。 */
    private static byte networkState(TileEntity te) {
        if (te instanceof ae2.me.helpers.IGridConnectedTile) {
            return (byte) (((ae2.me.helpers.IGridConnectedTile) te).getMainNode().isActive() ? 1 : 0);
        }
        var host = te.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, null);
        if (host == null) return -1;
        var node = host.getGridNode(null);
        return (byte) (node != null && node.isActive() ? 1 : 0);
    }

    /** 各类仓室的缓存量统计（无缓存概念返回 -1）。 */
    private static long bufferedAmount(TileEntity te) {
        if (te instanceof TileMEAsyncItemOutputBus) {
            return ((TileMEAsyncItemOutputBus) te).getBufferedAmount();
        }
        if (te instanceof TileMEAsyncFluidOutputHatch) {
            return ((TileMEAsyncFluidOutputHatch) te).getBufferedAmount();
        }
        if (te instanceof TileMEOutputAssembly) {
            TileMEOutputAssembly assembly = (TileMEOutputAssembly) te;
            return assembly.getBufferedItemAmount() + assembly.getBufferedFluidAmount();
        }
        if (te instanceof TileInputAssembly) {
            TileInputAssembly assembly = (TileInputAssembly) te;
            return assembly.getBufferedItemAmount() + assembly.getBufferedFluidAmount();
        }
        if (te instanceof TileMEPatternAssembly) {
            TileMEPatternAssembly assembly = (TileMEPatternAssembly) te;
            return assembly.getTotalBufferedInputItems() + assembly.getTotalBufferedInputFluids()
                    + assembly.getTotalBufferedOutputItems() + assembly.getTotalBufferedOutputFluids();
        }
        if (te instanceof TileInventory) {
            return countInventory(((TileInventory) te).getInventory());
        }
        IItemHandler items = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (items != null) return countInventory(items);
        // MMCE 能源仓（编译期无法直接引用 TileEnergyHatch，走 Forge 能量 Capability）
        IEnergyStorage energy = te.getCapability(CapabilityEnergy.ENERGY, null);
        if (energy != null) {
            return energy.getEnergyStored();
        }
        // MMCE 流体仓（编译期无法直接引用 TileFluidTank，走 Forge 流体 Capability）
        IFluidHandler fluidHandler = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (fluidHandler != null) {
            long sum = 0;
            for (IFluidTankProperties tank : fluidHandler.getTankProperties()) {
                FluidStack contents = tank.getContents();
                if (contents != null) {
                    sum += contents.amount;
                }
            }
            return sum;
        }
        return -1;
    }

    private static long countInventory(@Nullable IItemHandler inv) {
        if (inv == null) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            sum += inv.getStackInSlot(i).getCount();
        }
        return sum;
    }

    /** 清空单个仓室的缓存。返回是否有内容被清空。 */
    private static boolean clearBuffer(TileEntity te) {
        boolean cleared = false;
        if (te instanceof TileMEAsyncItemOutputBus) {
            TileMEAsyncItemOutputBus bus = (TileMEAsyncItemOutputBus) te;
            cleared = !bus.getItemBuffer().isEmpty();
            bus.getItemBuffer().clear();
            return cleared;
        }
        if (te instanceof TileMEAsyncFluidOutputHatch) {
            TileMEAsyncFluidOutputHatch hatch = (TileMEAsyncFluidOutputHatch) te;
            cleared = !hatch.getFluidBuffer().isEmpty();
            hatch.getFluidBuffer().clear();
            return cleared;
        }
        if (te instanceof TileMEOutputAssembly) {
            TileMEOutputAssembly assembly = (TileMEOutputAssembly) te;
            cleared = !assembly.getItemBuffer().isEmpty() || !assembly.getFluidBuffer().isEmpty();
            assembly.getItemBuffer().clear();
            assembly.getFluidBuffer().clear();
            return cleared;
        }
        if (te instanceof TileInputAssembly) {
            TileInputAssembly assembly = (TileInputAssembly) te;
            cleared = !assembly.getItemBuffer().isEmpty() || !assembly.getFluidBuffer().isEmpty();
            assembly.getItemBuffer().clear();
            assembly.getFluidBuffer().clear();
            return cleared;
        }
        if (te instanceof TileMEPatternAssembly) {
            for (PatternAssemblySlot slot : ((TileMEPatternAssembly) te).getSlots()) {
                cleared |= !slot.getInputItemBuffer().isEmpty() || !slot.getInputFluidBuffer().isEmpty()
                        || !slot.getOutputItemBuffer().isEmpty() || !slot.getOutputFluidBuffer().isEmpty();
                slot.getInputItemBuffer().clear();
                slot.getInputFluidBuffer().clear();
                slot.getOutputItemBuffer().clear();
                slot.getOutputFluidBuffer().clear();
            }
            return cleared;
        }
        if (te instanceof TileInventory) {
            return clearInventory(((TileInventory) te).getInventory());
        }
        IItemHandler items = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (items != null) {
            for (int i = 0; i < items.getSlots(); i++) {
                cleared |= !items.extractItem(i, Integer.MAX_VALUE, false).isEmpty();
            }
            return cleared;
        }
        // 能源仓不清空：能量是瞬时资源而非积压缓存
        // MMCE 流体仓：走 Forge 流体 Capability 逐罐抽空
        IFluidHandler fluidHandler = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (fluidHandler != null) {
            for (IFluidTankProperties tank : fluidHandler.getTankProperties()) {
                FluidStack contents = tank.getContents();
                if (contents != null && contents.amount > 0) {
                    cleared = true;
                    fluidHandler.drain(contents, true);
                }
            }
            return cleared;
        }
        return false;
    }

    private static boolean clearInventory(@Nullable IOInventory inv) {
        if (inv == null) {
            return false;
        }
        boolean cleared = false;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                cleared = true;
            }
            inv.setStackInSlot(i, ItemStack.EMPTY);
        }
        return cleared;
    }

    private static void sort(List<Entry> entries, int sortBy) {
        switch (sortBy) {
            case SORT_BUFFERED:
                entries.sort(Comparator.comparingLong((Entry e) -> e.buffered).reversed());
                break;
            case SORT_TICK_TIME:
                entries.sort(Comparator.comparingLong((Entry e) -> e.avgNanos).reversed());
                break;
            case SORT_TYPE:
            default:
                entries.sort(Comparator.comparingInt((Entry e) -> e.type.ordinal())
                        .thenComparing(e -> e.name));
                break;
        }
    }
}

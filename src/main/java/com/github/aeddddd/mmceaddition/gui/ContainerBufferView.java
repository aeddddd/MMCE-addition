package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.network.PacketHandler;
import com.github.aeddddd.mmceaddition.network.PktBufferViewSync;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidActionResult;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 缓冲视图容器：供各类无原生 GUI 的仓室（异步输出总线/流体仓、输出总成、输入总成）
 * 以滚动网格形式查看与存取内部 Long 缓冲。
 * <p>
 * 网格内容（物品/流体条目 + Long 数量）不参与原版槽位同步：
 * 服务端按滚动窗口（{@link #VISIBLE} 格）计算快照哈希，变化时经
 * {@link PktBufferViewSync} 推送可见窗口内容；点击与滚动分别经
 * PktBufferViewClick / PktBufferViewScroll 上行，服务端权威执行。
 * 玩家背包仍为原版槽位，shift 点击背包物品直接存入物品缓冲。
 */
public class ContainerBufferView extends Container {

    /** 网格列数与可视行数。 */
    public static final int COLS = 9;
    public static final int ROWS = 4;
    public static final int VISIBLE = COLS * ROWS;

    private final TileEntity tile;
    private final IBufferViewProvider provider;
    private final EntityPlayer player;

    /** 滚动偏移（条目数，COLS 的倍数，服务端权威）。 */
    private int scrollOffset = 0;
    /** 条目总数（服务端计算，随窗口包同步到客户端）。 */
    private int totalEntries = 0;
    /** 网络能源显示值（仅 hasViewEnergy 时有意义）。 */
    private long energy = 0;

    /** 上次同步的窗口哈希；Long.MIN_VALUE 表示尚未发送过。 */
    private long lastSyncHash = Long.MIN_VALUE;

    /** 客户端窗口条目缓存（长度 VISIBLE，null 为空格）。 */
    private final List<Entry> clientEntries = new ArrayList<>(VISIBLE);

    public ContainerBufferView(TileEntity tile, EntityPlayer player) {
        this.tile = tile;
        this.provider = (IBufferViewProvider) tile;
        this.player = player;
        for (int i = 0; i < VISIBLE; i++) {
            clientEntries.add(null);
        }
        // 玩家背包：cell border (7,115) → 槽位 (8,116)
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(new Slot(player.inventory, j + i * 9 + 9, 8 + j * 18, 116 + i * 18));
            }
        }
        // 快捷栏
        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(player.inventory, i, 8 + i * 18, 174));
        }
    }

    public TileEntity getTile() {
        return tile;
    }

    public BlockPos getPos() {
        return tile.getPos();
    }

    public IBufferViewProvider getProvider() {
        return provider;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public int getTotalEntries() {
        return totalEntries;
    }

    public long getEnergy() {
        return energy;
    }

    /** 客户端读取窗口条目（可能为 null 的空格）。 */
    @Nullable
    public Entry getClientEntry(int cell) {
        return cell >= 0 && cell < VISIBLE ? clientEntries.get(cell) : null;
    }

    /** 客户端：应用服务端推送的窗口内容。 */
    public void setClientWindow(int scrollOffset, int totalEntries, long energy, List<Entry> entries) {
        this.scrollOffset = scrollOffset;
        this.totalEntries = totalEntries;
        this.energy = energy;
        for (int i = 0; i < VISIBLE; i++) {
            clientEntries.set(i, i < entries.size() ? entries.get(i) : null);
        }
    }

    public boolean hasViewEnergy() {
        return provider.hasViewEnergy();
    }

    @Override
    public boolean canInteractWith(@Nonnull EntityPlayer playerIn) {
        return tile.getWorld().getTileEntity(tile.getPos()) == tile
                && playerIn.getDistanceSq(tile.getPos().add(0.5, 0.5, 0.5)) <= 64.0;
    }

    // ==================== 条目快照 ====================

    /**
     * 缓冲条目：物品（单件模板 + Long 数量）或流体（Fluid + Long 数量）。
     */
    public static final class Entry {
        public static final int ITEM = 1;
        public static final int FLUID = 2;

        public final int type;
        /** ITEM：单件模板堆。 */
        public final ItemStack stack;
        /** FLUID：流体类型。 */
        public final Fluid fluid;
        public final long amount;
        /** 排序键，保证列表顺序稳定（滚动位置不随数量波动）。 */
        final String sortKey;

        private Entry(int type, ItemStack stack, Fluid fluid, long amount, String sortKey) {
            this.type = type;
            this.stack = stack;
            this.fluid = fluid;
            this.amount = amount;
            this.sortKey = sortKey;
        }

        static Entry item(ItemVariant variant, long amount) {
            ItemStack single = variant.toSingleStack();
            String key = single.getItem().getRegistryName() + "#" + single.getMetadata();
            return new Entry(ITEM, single, null, amount, key);
        }

        static Entry fluid(Fluid fluid, long amount) {
            return new Entry(FLUID, ItemStack.EMPTY, fluid, amount, "f:" + fluid.getName());
        }

        /** 客户端反序列化用：物品条目。 */
        public static Entry clientItem(ItemStack stack, long amount) {
            stack.setCount(1);
            return new Entry(ITEM, stack, null, amount, "");
        }

        /** 客户端反序列化用：流体条目。 */
        public static Entry clientFluid(Fluid fluid, long amount) {
            return new Entry(FLUID, ItemStack.EMPTY, fluid, amount, "");
        }
    }

    /**
     * 构建当前缓冲条目列表：物品在前（按注册名+meta 排序），流体在后（按流体名排序）。
     */
    private List<Entry> buildEntries() {
        List<Entry> list = new ArrayList<>();
        LongItemBuffer ib = provider.getViewItemBuffer();
        if (ib != null) {
            for (Map.Entry<ItemVariant, Long> e : ib.snapshot().entrySet()) {
                if (e.getValue() > 0) {
                    list.add(Entry.item(e.getKey(), e.getValue()));
                }
            }
            list.sort(Comparator.comparing(en -> en.sortKey));
        }
        LongFluidBuffer fb = provider.getViewFluidBuffer();
        if (fb != null) {
            List<Entry> fluids = new ArrayList<>();
            for (Map.Entry<Fluid, Long> e : fb.snapshot().entrySet()) {
                if (e.getValue() > 0) {
                    fluids.add(Entry.fluid(e.getKey(), e.getValue()));
                }
            }
            fluids.sort(Comparator.comparing(en -> en.sortKey));
            list.addAll(fluids);
        }
        return list;
    }

    /** 最大滚动偏移（条目数，按行对齐）。 */
    public static int maxScrollOffset(int totalEntries) {
        int rows = (totalEntries + COLS - 1) / COLS;
        return Math.max(0, rows - ROWS) * COLS;
    }

    /** 客户端经滚动包请求偏移；服务端钳制后由 detectAndSendChanges 推送新窗口。 */
    public void handleScroll(int offset) {
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(totalEntries), offset));
    }

    // ==================== 同步 ====================

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (player.world.isRemote) {
            return;
        }
        List<Entry> entries = buildEntries();
        totalEntries = entries.size();
        int maxScroll = maxScrollOffset(totalEntries);
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
        energy = provider.hasViewEnergy() ? provider.getViewEnergy() : 0;

        // 可见窗口哈希：内容/滚动/总数/能源任一变化即重推
        long hash = 1125899906842597L;
        hash = 31 * hash + scrollOffset;
        hash = 31 * hash + totalEntries;
        hash = 31 * hash + energy;
        for (int i = 0; i < VISIBLE; i++) {
            int idx = scrollOffset + i;
            Entry e = idx < entries.size() ? entries.get(idx) : null;
            hash = 31 * hash + (e == null ? 0 : e.type);
            if (e != null) {
                hash = 31 * hash + e.sortKey.hashCode();
                hash = 31 * hash + e.amount;
            }
        }
        if (hash == lastSyncHash) {
            return;
        }
        lastSyncHash = hash;

        List<Entry> window = new ArrayList<>(VISIBLE);
        for (int i = 0; i < VISIBLE; i++) {
            int idx = scrollOffset + i;
            if (idx >= entries.size()) {
                break;
            }
            window.add(entries.get(idx));
        }
        PktBufferViewSync pkt = new PktBufferViewSync(scrollOffset, totalEntries, energy, window);
        for (IContainerListener listener : listeners) {
            if (listener instanceof EntityPlayerMP) {
                PacketHandler.INSTANCE.sendTo(pkt, (EntityPlayerMP) listener);
            }
        }
    }

    // ==================== 点击交互（服务端权威） ====================

    /**
     * 处理网格点击。
     * <ul>
     *   <li>光标为流体容器：装满的倒入缓冲；空容器从缓冲汲出（点击流体格时限定该流体）</li>
     *   <li>光标为普通物品：整组存入物品缓冲</li>
     *   <li>空光标点击物品格：左键取一组 / 右键半组，shift 直接入背包</li>
     * </ul>
     */
    public void handleCellClick(int cell, int button, boolean shift, EntityPlayer clicker) {
        if (cell < 0 || cell >= VISIBLE || clicker != player) {
            return;
        }
        LongItemBuffer ib = provider.getViewItemBuffer();
        LongFluidBuffer fb = provider.getViewFluidBuffer();

        List<Entry> entries = buildEntries();
        int idx = scrollOffset + cell;
        Entry entry = idx < entries.size() ? entries.get(idx) : null;

        ItemStack cursor = clicker.inventory.getItemStack();
        IFluidHandlerItem cursorFluid = cursor.isEmpty() ? null : FluidUtil.getFluidHandler(cursor);

        // 1) 流体容器交互（仅当该仓室有流体缓冲）
        if (fb != null && cursorFluid != null) {
            FluidStack contained = cursorFluid.drain(Integer.MAX_VALUE, false);
            boolean modified;
            if (contained != null && contained.amount > 0) {
                FluidActionResult result = FluidUtil.tryEmptyContainer(cursor,
                        new BufferFluidHandler(fb, null), Integer.MAX_VALUE, clicker, true);
                modified = applyFluidResult(clicker, result);
            } else {
                Fluid filter = entry != null && entry.type == Entry.FLUID ? entry.fluid : null;
                if (fb.isEmpty() && filter == null) {
                    return;
                }
                FluidActionResult result = FluidUtil.tryFillContainer(cursor,
                        new BufferFluidHandler(fb, filter), Integer.MAX_VALUE, clicker, true);
                modified = applyFluidResult(clicker, result);
            }
            if (modified) {
                provider.onViewBufferModified();
                tile.markDirty();
            }
            return;
        }

        // 2) 光标物品存入（与原版槽位一致：左键整组放入，右键只放 1 个）
        if (!cursor.isEmpty()) {
            if (ib == null) {
                return;
            }
            if (button == 1) {
                ItemStack one = cursor.copy();
                one.setCount(1);
                if (ib.insert(one).isEmpty()) {
                    cursor.shrink(1);
                }
            } else {
                ItemStack remainder = ib.insert(cursor);
                clicker.inventory.setItemStack(remainder);
            }
            provider.onViewBufferModified();
            tile.markDirty();
            return;
        }

        // 3) 空光标取出物品（左键取一组 / 右键取半组，以 min(可用量, maxStack) 为基准，同原版槽位语义）
        if (entry == null || entry.type != Entry.ITEM || ib == null) {
            return;
        }
        int maxStack = Math.max(1, entry.stack.getMaxStackSize());
        long shown = Math.min(entry.amount, maxStack);
        long want = button == 0 ? shown : Math.max(1, (shown + 1) / 2);
        if (shift) {
            // shift：直接合并入背包，放不下的退回缓冲
            long taken = ib.extract(new ItemVariant(entry.stack), maxStack);
            if (taken <= 0) {
                return;
            }
            ItemStack out = entry.stack.copy();
            out.setCount((int) taken);
            mergeItemStack(out, 0, 36, false);
            if (!out.isEmpty()) {
                ib.insert(out);
            }
        } else {
            long taken = ib.extract(new ItemVariant(entry.stack), want);
            if (taken <= 0) {
                return;
            }
            ItemStack out = entry.stack.copy();
            out.setCount((int) taken);
            clicker.inventory.setItemStack(out);
        }
        provider.onViewBufferModified();
        tile.markDirty();
    }

    /** 应用流体容器交互结果：单组直接替换光标，多组拆分入背包。 */
    private static boolean applyFluidResult(EntityPlayer clicker, FluidActionResult result) {
        if (!result.isSuccess()) {
            return false;
        }
        ItemStack cursor = clicker.inventory.getItemStack();
        if (cursor.getCount() <= 1) {
            clicker.inventory.setItemStack(result.getResult());
        } else {
            cursor.shrink(1);
            if (!clicker.inventory.addItemStackToInventory(result.getResult())) {
                clicker.dropItem(result.getResult(), false);
            }
        }
        return true;
    }

    // ==================== shift 存入 ====================

    /**
     * 背包槽 shift 点击：物品直接存入物品缓冲（无物品缓冲的仓室不响应）。
     */
    @Nonnull
    @Override
    public ItemStack transferStackInSlot(EntityPlayer playerIn, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        LongItemBuffer ib = provider.getViewItemBuffer();
        if (ib == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();
        int before = stack.getCount();
        ItemStack remainder = ib.insert(stack);
        int inserted = before - (remainder.isEmpty() ? 0 : remainder.getCount());
        if (inserted <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        copy.setCount(inserted);
        if (remainder.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            stack.setCount(remainder.getCount());
            slot.onSlotChanged();
        }
        provider.onViewBufferModified();
        tile.markDirty();
        return copy;
    }

    /**
     * Long 流体缓冲的 IFluidHandler 适配器，供 Forge 流体容器交互
     * （{@link FluidUtil#tryFillContainer} / {@link FluidUtil#tryEmptyContainer}）使用。
     * 可选 filter 限定只汲出指定流体（点击具体流体格时）。
     */
    private static class BufferFluidHandler implements IFluidHandler {

        private final LongFluidBuffer buffer;
        @Nullable
        private final Fluid filter;

        BufferFluidHandler(LongFluidBuffer buffer, @Nullable Fluid filter) {
            this.buffer = buffer;
            this.filter = filter;
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            if (resource == null || (filter != null && resource.getFluid() != filter)) {
                return 0;
            }
            return buffer.fill(resource, doFill);
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            if (resource == null || (filter != null && resource.getFluid() != filter)) {
                return null;
            }
            long taken = Math.min(buffer.getAmount(resource.getFluid()), resource.amount);
            if (taken <= 0) {
                return null;
            }
            if (doDrain) {
                taken = buffer.extract(resource.getFluid(), taken);
            }
            return new FluidStack(resource.getFluid(), (int) taken);
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            Fluid target = filter;
            if (target == null) {
                for (Map.Entry<Fluid, Long> e : buffer.snapshot().entrySet()) {
                    if (e.getValue() > 0) {
                        target = e.getKey();
                        break;
                    }
                }
            }
            if (target == null) {
                return null;
            }
            long taken = Math.min(buffer.getAmount(target), maxDrain);
            if (taken <= 0) {
                return null;
            }
            if (doDrain) {
                taken = buffer.extract(target, taken);
            }
            return new FluidStack(target, (int) taken);
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            final FluidStack contents;
            Fluid first = null;
            long amount = 0;
            for (Map.Entry<Fluid, Long> e : buffer.snapshot().entrySet()) {
                if (e.getValue() > 0) {
                    first = e.getKey();
                    amount = e.getValue();
                    break;
                }
            }
            contents = first == null ? null : new FluidStack(first, (int) Math.min(amount, Integer.MAX_VALUE));
            return new IFluidTankProperties[]{new IFluidTankProperties() {
                @Nullable
                @Override
                public FluidStack getContents() {
                    return contents;
                }

                @Override
                public int getCapacity() {
                    return Integer.MAX_VALUE;
                }

                @Override
                public boolean canFill() {
                    return true;
                }

                @Override
                public boolean canDrain() {
                    return true;
                }

                @Override
                public boolean canFillFluidType(FluidStack fluidStack) {
                    return true;
                }

                @Override
                public boolean canDrainFluidType(@Nullable FluidStack fluidStack) {
                    return true;
                }
            }};
        }
    }
}

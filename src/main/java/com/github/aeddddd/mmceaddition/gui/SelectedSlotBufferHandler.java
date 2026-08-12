package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.tile.slot.PatternAssemblySlot;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 为当前选中的样板槽提供输入/输出缓冲的 GUI 视图。
 * <p>
 * 前 18 个槽位映射到选中槽的输入缓冲（AE pushPattern 写入的原料），
 * 后 18 个槽位映射到选中槽的输出缓冲（机器产物）。
 * 面板只是一页视图：滚轮滚动偏移（输入/输出共用）后可访问缓冲中的全部变体，
 * 容量不受面板槽位数限制。
 * 玩家可以直接从槽位中取出材料，就像原版样板总成一样。
 * <p>
 * 同步策略：服务端直接从 Long 缓冲计算槽位内容；客户端不读 Tile 的缓冲数据，
 * 而是通过 Container 标准同步（SPacketSetSlot → putStack → setStackInSlot）
 * 维护一份本地缓存 {@link #clientCache}，保证右侧面板显示始终与服务端一致。
 */
public class SelectedSlotBufferHandler implements IItemHandlerModifiable {

    public static final int SLOTS_PER_BUFFER = 18;
    public static final int TOTAL_SLOTS = SLOTS_PER_BUFFER * 2;

    private final ContainerMEPatternAssembly container;

    /** 客户端同步缓存：内容由服务端的 detectAndSendChanges 推送。 */
    private final ItemStack[] clientCache = new ItemStack[TOTAL_SLOTS];

    /** 客户端真实数量缓存：由 PktMEPatternAssemblyBufferCounts 同步（显示堆数量受 maxStackSize 钳制）。 */
    private final long[] clientTrueCounts = new long[TOTAL_SLOTS];

    public SelectedSlotBufferHandler(ContainerMEPatternAssembly container) {
        this.container = container;
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            clientCache[i] = ItemStack.EMPTY;
        }
    }

    /**
     * 获取槽位的真实数量（long）。
     * 服务端从 Long 缓冲计算；客户端读取同步缓存。
     */
    public long getTrueCount(int slot) {
        if (slot < 0 || slot >= TOTAL_SLOTS) {
            return 0;
        }
        if (isClient()) {
            return clientTrueCounts[slot];
        }
        LongItemBuffer buffer = getBuffer(slot < SLOTS_PER_BUFFER);
        if (buffer == null) {
            return 0;
        }
        Map.Entry<ItemVariant, Long> entry = getEntry(buffer, variantIndex(slot));
        return entry == null ? 0 : entry.getValue();
    }

    /** 客户端：接收服务端推送的真实数量。 */
    public void setClientTrueCounts(long[] counts) {
        int len = Math.min(counts.length, TOTAL_SLOTS);
        System.arraycopy(counts, 0, clientTrueCounts, 0, len);
    }

    private boolean isClient() {
        return container.getOwner().getWorld() != null && container.getOwner().getWorld().isRemote;
    }

    @Override
    public int getSlots() {
        return TOTAL_SLOTS;
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= TOTAL_SLOTS) {
            return ItemStack.EMPTY;
        }
        if (isClient()) {
            return clientCache[slot];
        }
        LongItemBuffer buffer = getBuffer(slot < SLOTS_PER_BUFFER);
        if (buffer == null) {
            return ItemStack.EMPTY;
        }
        Map.Entry<ItemVariant, Long> entry = getEntry(buffer, variantIndex(slot));
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = entry.getKey().toSingleStack();
        int displayCount = (int) Math.min(entry.getValue(), (long) stack.getMaxStackSize());
        if (displayCount <= 0) {
            return ItemStack.EMPTY;
        }
        stack.setCount(displayCount);
        return stack;
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        // 缓冲槽仅用于显示和取出，不允许直接放入。
        return stack;
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot < 0 || slot >= TOTAL_SLOTS || amount <= 0) {
            return ItemStack.EMPTY;
        }
        if (isClient()) {
            // 客户端点击预测：基于本地缓存模拟取出，服务端随后会推送权威数据纠正。
            ItemStack cached = clientCache[slot];
            if (cached.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int toExtract = Math.min(amount, cached.getCount());
            ItemStack result = cached.copy();
            result.setCount(toExtract);
            if (!simulate) {
                cached.shrink(toExtract);
            }
            return result;
        }

        LongItemBuffer buffer = getBuffer(slot < SLOTS_PER_BUFFER);
        if (buffer == null) {
            return ItemStack.EMPTY;
        }
        Map.Entry<ItemVariant, Long> entry = getEntry(buffer, variantIndex(slot));
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ItemVariant variant = entry.getKey();
        ItemStack prototype = variant.toSingleStack();
        int toExtract = (int) Math.min(amount, Math.min(entry.getValue(), (long) prototype.getMaxStackSize()));
        if (toExtract <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = prototype.copy();
        result.setCount(toExtract);
        if (!simulate) {
            buffer.extract(variant, toExtract);
            container.getOwner().markDirty();
        }
        return result;
    }

    /**
     * 把物品退回对应缓冲（shift 取出时背包放不下的剩余部分）。
     * 服务端写真实 Long 缓冲；客户端无需处理，缓存会由同步纠正。
     */
    public void returnToBuffer(int slot, @Nonnull ItemStack stack) {
        if (isClient() || stack.isEmpty() || slot < 0 || slot >= TOTAL_SLOTS) {
            return;
        }
        LongItemBuffer buffer = getBuffer(slot < SLOTS_PER_BUFFER);
        if (buffer == null) {
            return;
        }
        buffer.insert(stack);
        container.getOwner().markDirty();
    }

    @Override
    public int getSlotLimit(int slot) {
        ItemStack stack = getStackInSlot(slot);
        return stack.isEmpty() ? 64 : stack.getMaxStackSize();
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        // 客户端：作为 Container 标准同步的落点，写入本地缓存。
        // 服务端：缓冲数据由 Long 缓冲权威持有，不允许外部直接设置。
        if (isClient() && slot >= 0 && slot < TOTAL_SLOTS) {
            clientCache[slot] = stack;
        }
    }

    @Nonnull
    public TileMEPatternAssembly getOwner() {
        return container.getOwner();
    }

    /** 槽位 → 缓冲变体索引：面板内序号 + 滚动偏移（输入/输出缓冲共用同一偏移）。 */
    private int variantIndex(int slot) {
        int local = slot < SLOTS_PER_BUFFER ? slot : slot - SLOTS_PER_BUFFER;
        return local + container.getBufferScrollOffset();
    }

    private LongItemBuffer getBuffer(boolean input) {
        int selected = container.getSelectedSlot();
        if (selected < 0 || selected >= TileMEPatternAssembly.PATTERNS) {
            return null;
        }
        PatternAssemblySlot slot = container.getOwner().getSlots()[selected];
        if (slot == null) {
            return null;
        }
        return input ? slot.getInputItemBuffer() : slot.getOutputItemBuffer();
    }

    /**
     * 按稳定的排序规则取缓冲中的第 index 个变体。
     * 排序保证服务端多次计算（以及连续同步）时槽位与变体的映射稳定。
     */
    private Map.Entry<ItemVariant, Long> getEntry(LongItemBuffer buffer, int index) {
        List<Map.Entry<ItemVariant, Long>> entries = new ArrayList<>(buffer.snapshot().entrySet());
        if (index >= entries.size()) {
            return null;
        }
        entries.sort(Comparator.comparing(e -> {
            ItemStack stack = e.getKey().toSingleStack();
            String nbt = stack.hasTagCompound() ? stack.getTagCompound().toString() : "";
            return stack.getTranslationKey() + ":" + stack.getMetadata() + ":" + nbt;
        }));
        return entries.get(index);
    }
}

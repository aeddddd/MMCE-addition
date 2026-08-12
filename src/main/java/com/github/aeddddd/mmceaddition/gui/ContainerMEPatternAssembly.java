package com.github.aeddddd.mmceaddition.gui;

import appeng.api.implementations.ICraftingPatternItem;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.tile.slot.PatternAssemblySlot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;

/**
 * ME 样板总成容器。
 * <p>
 * 布局与 MMCE 的 ME 样板供应器 GUI（textures/gui/mepatternprovider.png，256x188 主区域）对齐：
 * <ul>
 *   <li>36 个样板槽：9x4，起始 (8, 28)，与 MMCE 一致</li>
 *   <li>玩家背包：起始 (8, 114)，快捷栏 y=172，与 MMCE 一致</li>
 *   <li>右侧缓冲面板：选中槽的输入缓冲 3x3（起始 (190, 20)）与输出缓冲 3x3（起始 (190, 92)）</li>
 *   <li>12 个催化剂槽：6x2，起始 (8, 204)，位于向下扩展的面板区域</li>
 * </ul>
 * 选中样板槽的状态保存在容器实例上（每个玩家独立），
 * 通过 window property 同步到客户端，避免了多名玩家同时打开时互相干扰。
 */
public class ContainerMEPatternAssembly extends Container {

    public static final int PATTERNS = TileMEPatternAssembly.PATTERNS;
    public static final int CATALYST_SLOTS = TileMEPatternAssembly.SLOT_CATALYST_COUNT;
    public static final int BUFFER_SLOTS = SelectedSlotBufferHandler.TOTAL_SLOTS;
    public static final int PLAYER_START = PATTERNS + CATALYST_SLOTS + BUFFER_SLOTS;

    private final TileMEPatternAssembly owner;
    private final SelectedSlotBufferHandler bufferHandler;

    /** 当前选中的样板槽索引（每个容器实例独立，服务端权威）。 */
    private int selectedSlot = 0;

    /** 缓冲面板的滚动偏移（变体数，服务端权威；输入/输出缓冲共用）。 */
    private int bufferScrollOffset = 0;

    /** 当前选中槽缓冲的最大滚动偏移（服务端计算，window property 同步给客户端）。 */
    private int maxBufferScrollOffset = 0;

    // 客户端同步用：上次已同步的选中槽索引/滚动偏移/最大滚动偏移
    private int lastSyncedSelectedSlot = -1;
    private int lastSyncedScrollOffset = -1;
    private int lastSyncedMaxOffset = -1;

    // 服务端同步用：上次已推送的缓冲真实数量
    private final long[] lastSentBufferCounts = new long[BUFFER_SLOTS];

    public ContainerMEPatternAssembly(TileMEPatternAssembly owner, EntityPlayer player) {
        this.owner = owner;
        this.bufferHandler = new SelectedSlotBufferHandler(this);

        // 36 个样板槽：9 列 x 4 行，起始 (8, 28)
        for (int i = 0; i < PATTERNS; i++) {
            final int slotIndex = i;
            int x = 8 + (i % 9) * 18;
            int y = 28 + (i / 9) * 18;
            addSlotToContainer(new SlotItemHandler(owner.getPatterns(), slotIndex, x, y) {
                @Override
                public boolean isItemValid(ItemStack stack) {
                    return !stack.isEmpty() && stack.getItem() instanceof ICraftingPatternItem;
                }
            });
        }

        // 12 个催化剂槽：6 列 x 2 行，起始 (8, 230)
        CatalystItemHandler catalystHandler = new CatalystItemHandler(this);
        for (int i = 0; i < CATALYST_SLOTS; i++) {
            int x = 8 + (i % 6) * 18;
            int y = 230 + (i / 6) * 18;
            addSlotToContainer(new SlotItemHandler(catalystHandler, i, x, y) {
                @Override
                public boolean isItemValid(ItemStack stack) {
                    return !stack.isEmpty();
                }
            });
        }

        // 当前选中样板槽的输入/输出缓冲槽：右侧面板，3 列 × 6 行，
        // 输入缓冲起始 (190, 30)（完整落在列表凹槽 y28..151 内），输出缓冲起始 (190, 172)
        // （覆盖死槽位后的深灰区域起，向下无缝延伸到扩展面板右侧）。
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            int x = 190 + (i % 3) * 18;
            int y = (i < 18 ? 30 : 172) + ((i % 18) / 3) * 18;
            addSlotToContainer(new BufferSlot(bufferHandler, i, x, y));
        }

        // 玩家背包：起始 (8, 114)
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(new Slot(player.inventory, j + i * 9 + 9, 8 + j * 18, 114 + i * 18));
            }
        }

        // 玩家快捷栏
        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(player.inventory, i, 8 + i * 18, 172));
        }
    }

    public TileMEPatternAssembly getOwner() {
        return owner;
    }

    public SelectedSlotBufferHandler getBufferHandler() {
        return bufferHandler;
    }

    public int getSelectedSlot() {
        return selectedSlot;
    }

    /**
     * 设置当前选中的样板槽（带范围校验）。
     * 服务端由网络包处理器调用，客户端由 GUI 中键点击直接调用。
     */
    public void setSelectedSlot(int slot) {
        if (slot >= 0 && slot < PATTERNS) {
            this.selectedSlot = slot;
            // 切换槽位时回到顶部，避免沿用上一个槽的滚动位置。
            this.bufferScrollOffset = 0;
        }
    }

    public int getBufferScrollOffset() {
        return bufferScrollOffset;
    }

    /** 设置缓冲面板滚动偏移（下限 0；上限由服务端按缓冲实际变体数钳制）。 */
    public void setBufferScrollOffset(int offset) {
        this.bufferScrollOffset = Math.max(0, offset);
    }

    public int getMaxBufferScrollOffset() {
        return maxBufferScrollOffset;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return owner.getWorld().getTileEntity(owner.getPos()) == owner &&
                player.getDistanceSq(owner.getPos().add(0.5, 0.5, 0.5)) <= 64.0;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();

        // 注意：先判断再遍历 listener，避免多名玩家同时打开时只有第一个收到同步包。
        if (lastSyncedSelectedSlot != selectedSlot) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 0, selectedSlot);
            }
            lastSyncedSelectedSlot = selectedSlot;
        }

        // 滚动偏移上限随缓冲内容变化，超过上限时把偏移钳制回来并同步给客户端。
        maxBufferScrollOffset = computeMaxBufferScrollOffset();
        if (bufferScrollOffset > maxBufferScrollOffset) {
            bufferScrollOffset = maxBufferScrollOffset;
        }
        if (lastSyncedScrollOffset != bufferScrollOffset) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 1, bufferScrollOffset);
            }
            lastSyncedScrollOffset = bufferScrollOffset;
        }
        if (lastSyncedMaxOffset != maxBufferScrollOffset) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 2, maxBufferScrollOffset);
            }
            lastSyncedMaxOffset = maxBufferScrollOffset;
        }

        // 缓冲槽的真实数量（long）无法随 ItemStack 同步（数量被钳制到 maxStackSize），
        // 变化时单独推送，供客户端绘制大数量显示。
        long[] counts = new long[BUFFER_SLOTS];
        boolean changed = false;
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            counts[i] = bufferHandler.getTrueCount(i);
            if (counts[i] != lastSentBufferCounts[i]) {
                changed = true;
            }
        }
        if (changed) {
            System.arraycopy(counts, 0, lastSentBufferCounts, 0, BUFFER_SLOTS);
            com.github.aeddddd.mmceaddition.network.PktMEPatternAssemblyBufferCounts pkt =
                    new com.github.aeddddd.mmceaddition.network.PktMEPatternAssemblyBufferCounts(counts);
            for (IContainerListener listener : listeners) {
                if (listener instanceof net.minecraft.entity.player.EntityPlayerMP) {
                    com.github.aeddddd.mmceaddition.network.PacketHandler.INSTANCE.sendTo(
                            pkt, (net.minecraft.entity.player.EntityPlayerMP) listener);
                }
            }
        }
    }

    /** 服务端：按当前选中槽输入/输出缓冲的变体数计算最大滚动偏移。 */
    private int computeMaxBufferScrollOffset() {
        if (owner.getWorld() == null || owner.getWorld().isRemote) {
            return maxBufferScrollOffset;
        }
        PatternAssemblySlot slot = owner.getSlots()[selectedSlot];
        int max = Math.max(slot.getInputItemBuffer().snapshot().size(),
                slot.getOutputItemBuffer().snapshot().size());
        return Math.max(0, max - SelectedSlotBufferHandler.SLOTS_PER_BUFFER);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int data) {
        if (id == 0) {
            setSelectedSlot(data);
        } else if (id == 1) {
            bufferScrollOffset = data;
        } else if (id == 2) {
            maxBufferScrollOffset = data;
        }
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }

        // 缓冲槽 shift 取出：getStackInSlot 返回的只是显示用合成副本，
        // 直接 mergeItemStack 不会扣减服务端 Long 缓冲（会刷物品），
        // 必须经 extractItem 真实扣减，背包放不下的部分退回缓冲。
        if (index >= PATTERNS + CATALYST_SLOTS && index < PLAYER_START) {
            int bufferIndex = index - PATTERNS - CATALYST_SLOTS;
            ItemStack extracted = bufferHandler.extractItem(bufferIndex, 64, false);
            if (extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = extracted.copy();
            if (!mergeItemStack(extracted, PLAYER_START, PLAYER_START + 36, false)) {
                // 一个都放不进背包：整组退回缓冲，避免吞物品。
                bufferHandler.returnToBuffer(bufferIndex, extracted);
                return ItemStack.EMPTY;
            }
            if (!extracted.isEmpty()) {
                bufferHandler.returnToBuffer(bufferIndex, extracted);
            }
            slot.onSlotChanged();
            return result;
        }

        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();

        if (index < PLAYER_START) {
            // 从样板槽/催化剂槽/缓冲槽取出：移到玩家背包
            if (!mergeItemStack(stack, PLAYER_START, PLAYER_START + 36, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 从玩家背包：样板优先放入样板槽，否则放入催化剂槽
            if (!stack.isEmpty() && stack.getItem() instanceof ICraftingPatternItem) {
                if (!mergeItemStack(stack, 0, PATTERNS, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!mergeItemStack(stack, PATTERNS, PATTERNS + CATALYST_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (stack.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }

        return copy;
    }

    /**
     * 缓冲槽：只显示当前选中样板槽的输入/输出缓冲，允许玩家取出。
     */
    public static class BufferSlot extends SlotItemHandler {

        private final SelectedSlotBufferHandler handler;

        public BufferSlot(SelectedSlotBufferHandler handler, int slotIndex, int x, int y) {
            super(handler, slotIndex, x, y);
            this.handler = handler;
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canTakeStack(EntityPlayer player) {
            return !getStack().isEmpty();
        }

        @Override
        public ItemStack onTake(EntityPlayer player, ItemStack stack) {
            // 实际的抽取已经在 decrStackSize 中通过 handler.extractItem 完成，
            // 这里只需要通知 TileEntity 保存数据。
            if (!handler.getOwner().getWorld().isRemote) {
                handler.getOwner().markDirty();
            }
            return stack;
        }

        @Override
        public void onSlotChanged() {
            super.onSlotChanged();
            if (!handler.getOwner().getWorld().isRemote) {
                handler.getOwner().markDirty();
            }
        }
    }

    /**
     * 当前选中样板槽的催化剂存储，包装为标准 {@link IItemHandlerModifiable}。
     * <p>
     * 催化剂槽直接使用 {@link SlotItemHandler}：放入走 setStackInSlot/insertItem，
     * 取走出 decrStackSize → extractItem，Container 同步走 putStack → setStackInSlot，
     * 全部为拷贝语义，杜绝旧实现（虚拟 Inventory 持有 Tile 内活引用）导致的物品丢失问题。
     */
    private static class CatalystItemHandler implements IItemHandlerModifiable {

        private final ContainerMEPatternAssembly container;

        CatalystItemHandler(ContainerMEPatternAssembly container) {
            this.container = container;
        }

        private PatternAssemblySlot slot() {
            return container.getOwner().getSlots()[container.getSelectedSlot()];
        }

        private void save() {
            if (!container.getOwner().getWorld().isRemote) {
                container.getOwner().markDirty();
            }
        }

        @Override
        public int getSlots() {
            return CATALYST_SLOTS;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot().getCatalyst(slot);
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            slot().setCatalyst(slot, stack);
            save();
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack current = slot().getCatalyst(slot);
            if (current.isEmpty()) {
                int accepted = Math.min(stack.getCount(), stack.getMaxStackSize());
                if (!simulate) {
                    ItemStack placed = stack.copy();
                    placed.setCount(accepted);
                    setStackInSlot(slot, placed);
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(accepted);
                return remainder;
            }
            if (ItemStack.areItemsEqual(current, stack) && ItemStack.areItemStackTagsEqual(current, stack)) {
                int accepted = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());
                if (accepted <= 0) {
                    return stack;
                }
                if (!simulate) {
                    ItemStack grown = current.copy();
                    grown.grow(accepted);
                    setStackInSlot(slot, grown);
                }
                ItemStack remainder = stack.copy();
                remainder.shrink(accepted);
                return remainder;
            }
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack current = slot().getCatalyst(slot);
            if (current.isEmpty() || amount <= 0) {
                return ItemStack.EMPTY;
            }
            int extracted = Math.min(amount, current.getCount());
            ItemStack result = current.copy();
            result.setCount(extracted);
            if (!simulate) {
                if (extracted >= current.getCount()) {
                    setStackInSlot(slot, ItemStack.EMPTY);
                } else {
                    ItemStack shrunk = current.copy();
                    shrunk.shrink(extracted);
                    setStackInSlot(slot, shrunk);
                }
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }
    }
}

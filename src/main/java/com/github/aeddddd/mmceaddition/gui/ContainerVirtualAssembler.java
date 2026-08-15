package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.network.PacketHandler;
import com.github.aeddddd.mmceaddition.network.PktVirtualAssemblerCounts;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualAssembler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ClickType;
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
 * 虚拟装配台容器。
 * <p>
 * 布局（248x252，与 GUI 纹理坐标一致）：
 * <ul>
 *   <li>缓存槽可视窗口：9x2，起始 (8, 126)，滚轮翻页访问全部缓存槽</li>
 *   <li>输出槽：(224, 104)，只允许取出</li>
 *   <li>玩家背包：起始 (8, 176)，快捷栏 y=234</li>
 * </ul>
 * 缓存槽交互不走原版 slotClick 合并路径——原版会把物品 grow 到
 * getStackInSlot 返回的显示副本上导致吞物品，且按 maxStackSize 封顶 64。
 * 这里对缓存槽覆写 slotClick / transferStackInSlot，直接经可视窗口 handler
 * 以 int 计数合并/取出（服务端权威，客户端本地预测 + 同步纠正）。
 */
public class ContainerVirtualAssembler extends Container {

    public static final int BUFFER_VISIBLE = 18;
    public static final int OUTPUT_INDEX = BUFFER_VISIBLE;
    public static final int PLAYER_START = BUFFER_VISIBLE + 1;

    private final TileVirtualAssembler owner;
    private final BufferViewHandler bufferView;

    /** 当前选中的机器注册名（服务端权威；客户端点击时本地设置并发包）。 */
    private String selectedMachine;

    /** 缓存可视窗口滚动偏移（槽位数，9 的倍数，服务端权威）。 */
    private int bufferScrollOffset = 0;

    private int maxBufferScrollOffset = 0;
    /** 服务端计算的可装配份数（同步用，window property 走 short，钳制 32767）。 */
    private int assembleCount = 0;

    private int lastSyncedScrollOffset = -1;
    private int lastSyncedMaxOffset = -1;
    private int lastSyncedAssembleCount = -1;
    private final int[] lastSentCounts = new int[BUFFER_VISIBLE];

    public ContainerVirtualAssembler(TileVirtualAssembler owner, EntityPlayer player) {
        this.owner = owner;
        this.bufferView = new BufferViewHandler(this);

        // 缓存槽可视窗口：9 列 x 2 行，起始 (8, 126)
        for (int i = 0; i < BUFFER_VISIBLE; i++) {
            int x = 8 + (i % 9) * 18;
            int y = 126 + (i / 9) * 18;
            addSlotToContainer(new BufferSlot(bufferView, i, x, y));
        }

        // 输出槽：(224, 104)
        addSlotToContainer(new SlotItemHandler(owner.getOutput(), 0, 224, 104) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return false;
            }
        });

        // 玩家背包：起始 (8, 176)
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(new Slot(player.inventory, j + i * 9 + 9, 8 + j * 18, 176 + i * 18));
            }
        }
        // 快捷栏
        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(player.inventory, i, 8 + i * 18, 234));
        }
    }

    public TileVirtualAssembler getOwner() {
        return owner;
    }

    public BufferViewHandler getBufferView() {
        return bufferView;
    }

    public String getSelectedMachine() {
        return selectedMachine;
    }

    public void setSelectedMachine(String machineName) {
        this.selectedMachine = machineName;
    }

    public int getBufferScrollOffset() {
        return bufferScrollOffset;
    }

    public void setBufferScrollOffset(int offset) {
        this.bufferScrollOffset = Math.max(0, offset);
    }

    public int getMaxBufferScrollOffset() {
        return maxBufferScrollOffset;
    }

    /** 客户端读取服务端同步的可装配份数。 */
    public int getAssembleCount() {
        return assembleCount;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return owner.getWorld().getTileEntity(owner.getPos()) == owner &&
                player.getDistanceSq(owner.getPos().add(0.5, 0.5, 0.5)) <= 64.0;
    }

    // ==================== 缓存槽自定义点击（int 合并，绕过原版 64 上限与副本 grow） ====================

    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player) {
        if (slotId >= 0 && slotId < BUFFER_VISIBLE) {
            if (clickType == ClickType.QUICK_CRAFT) {
                // 禁止拖拽涂抹进缓存槽：原版路径会操作显示副本导致吞物品
                return ItemStack.EMPTY;
            }
            if (clickType == ClickType.PICKUP) {
                ItemStack cursor = player.inventory.getItemStack();
                if (!cursor.isEmpty()) {
                    if (cursor.getItem() instanceof ItemMachineData) {
                        return ItemStack.EMPTY;
                    }
                    if (dragType == 1) {
                        // 右键：放入 1 个
                        ItemStack one = cursor.copy();
                        one.setCount(1);
                        ItemStack remainder = bufferView.insertItem(slotId, one, false);
                        if (remainder.isEmpty()) {
                            cursor.shrink(1);
                        }
                    } else {
                        // 左键：整组放入（int 级合并）
                        ItemStack remainder = bufferView.insertItem(slotId, cursor, false);
                        player.inventory.setItemStack(remainder.isEmpty() ? ItemStack.EMPTY : remainder);
                    }
                    return player.inventory.getItemStack();
                }
                // 取出：左键一组 / 右键半组（不超过 maxStackSize，超出部分留在缓存中）
                ItemStack held = bufferView.getStackInSlot(slotId);
                if (held.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                int max = Math.max(1, held.getMaxStackSize());
                int amount = dragType == 0 ? max : Math.max(1, max / 2);
                ItemStack extracted = bufferView.extractItem(slotId, amount, false);
                player.inventory.setItemStack(extracted);
                return extracted;
            }
        }
        return super.slotClick(slotId, dragType, clickType, player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }

        // 缓存槽 shift 取出：经 extractItem 真实扣减，背包放不下的退回缓存
        if (index < BUFFER_VISIBLE) {
            ItemStack extracted = bufferView.extractItem(index, 64, false);
            if (extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = extracted.copy();
            if (!mergeItemStack(extracted, PLAYER_START, PLAYER_START + 36, false)) {
                bufferView.returnToBuffer(index, extracted);
                return ItemStack.EMPTY;
            }
            if (!extracted.isEmpty()) {
                bufferView.returnToBuffer(index, extracted);
            }
            slot.onSlotChanged();
            return result;
        }

        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();

        if (index == OUTPUT_INDEX) {
            // 输出槽 → 玩家背包
            if (!mergeItemStack(stack, PLAYER_START, PLAYER_START + 36, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.putStack(ItemStack.EMPTY);
            } else {
                slot.onSlotChanged();
            }
            return copy;
        }

        // 玩家背包 → 缓存槽：经可视窗口 handler 直接插入（int 级合并，服务端权威）
        if (stack.getItem() instanceof ItemMachineData) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack;
        for (int i = 0; i < BUFFER_VISIBLE && !remainder.isEmpty(); i++) {
            ItemStack inSlot = bufferView.getStackInSlot(i);
            if (inSlot.isEmpty() || new ItemVariant(inSlot).equals(new ItemVariant(stack))) {
                remainder = bufferView.insertItem(i, remainder, false);
            }
        }
        int moved = copy.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
        if (moved <= 0) {
            return ItemStack.EMPTY;
        }
        if (remainder.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            stack.setCount(remainder.getCount());
            slot.onSlotChanged();
        }
        return copy;
    }

    // ==================== 同步 ====================

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();

        maxBufferScrollOffset = Math.max(0, roundDownToRow(owner.getBuffer().getSlots() - BUFFER_VISIBLE));
        if (bufferScrollOffset > maxBufferScrollOffset) {
            bufferScrollOffset = maxBufferScrollOffset;
        }
        if (lastSyncedScrollOffset != bufferScrollOffset) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 0, bufferScrollOffset);
            }
            lastSyncedScrollOffset = bufferScrollOffset;
        }
        if (lastSyncedMaxOffset != maxBufferScrollOffset) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 1, maxBufferScrollOffset);
            }
            lastSyncedMaxOffset = maxBufferScrollOffset;
        }

        int k = selectedMachine == null ? 0 : Math.min(owner.getAssembleCount(selectedMachine), 32767);
        assembleCount = k;
        if (lastSyncedAssembleCount != assembleCount) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 2, assembleCount);
            }
            lastSyncedAssembleCount = assembleCount;
        }

        // 缓存槽真实数量（int）无法随 ItemStack 同步（数量为 byte），变化时单独推送
        int[] counts = new int[BUFFER_VISIBLE];
        boolean changed = false;
        for (int i = 0; i < BUFFER_VISIBLE; i++) {
            counts[i] = bufferView.getTrueCount(i);
            if (counts[i] != lastSentCounts[i]) {
                changed = true;
            }
        }
        if (changed) {
            System.arraycopy(counts, 0, lastSentCounts, 0, BUFFER_VISIBLE);
            PktVirtualAssemblerCounts pkt = new PktVirtualAssemblerCounts(counts);
            for (IContainerListener listener : listeners) {
                if (listener instanceof EntityPlayerMP) {
                    PacketHandler.INSTANCE.sendTo(pkt, (EntityPlayerMP) listener);
                }
            }
        }
    }

    private static int roundDownToRow(int slots) {
        return Math.max(0, (slots / 9) * 9);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int data) {
        if (id == 0) {
            bufferScrollOffset = data;
        } else if (id == 1) {
            maxBufferScrollOffset = data;
        } else if (id == 2) {
            assembleCount = data;
        }
    }

    /**
     * 缓存槽：绑定可视窗口 handler；放入/取出逻辑全部由容器自定义 slotClick 处理。
     */
    public static class BufferSlot extends SlotItemHandler {

        private final BufferViewHandler handler;

        public BufferSlot(BufferViewHandler handler, int slotIndex, int x, int y) {
            super(handler, slotIndex, x, y);
            this.handler = handler;
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return !stack.isEmpty() && handler.isItemValidForSlot(getSlotIndex(), stack);
        }

        @Override
        public int getItemStackLimit(ItemStack stack) {
            return Integer.MAX_VALUE;
        }

        @Override
        public void onSlotChanged() {
            super.onSlotChanged();
            if (!handler.container.getOwner().getWorld().isRemote) {
                handler.container.getOwner().markDirty();
            }
        }
    }

    /**
     * 缓存槽可视窗口：18 个槽位映射到底层缓存的 [scrollOffset, scrollOffset+18)。
     * <p>
     * 服务端直接代理 Tile 缓存（真写）；客户端维护本地缓存做点击预测，
     * 真实数量（int）由 {@link PktVirtualAssemblerCounts} 同步，显示堆只作占位。
     */
    public static class BufferViewHandler implements IItemHandlerModifiable {

        private final ContainerVirtualAssembler container;

        /** 客户端同步缓存。 */
        private final ItemStack[] clientCache = new ItemStack[BUFFER_VISIBLE];
        /** 客户端真实数量缓存（int）。 */
        private final int[] clientTrueCounts = new int[BUFFER_VISIBLE];

        public BufferViewHandler(ContainerVirtualAssembler container) {
            this.container = container;
            for (int i = 0; i < BUFFER_VISIBLE; i++) {
                clientCache[i] = ItemStack.EMPTY;
            }
        }

        private boolean isClient() {
            return container.getOwner().getWorld() != null && container.getOwner().getWorld().isRemote;
        }

        private int actualSlot(int slot) {
            return container.getBufferScrollOffset() + slot;
        }

        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            return !(stack.getItem() instanceof ItemMachineData);
        }

        public int getTrueCount(int slot) {
            if (slot < 0 || slot >= BUFFER_VISIBLE) {
                return 0;
            }
            if (isClient()) {
                return clientTrueCounts[slot];
            }
            ItemStack stack = container.getOwner().getBuffer().getStackInSlot(actualSlot(slot));
            return stack.isEmpty() ? 0 : stack.getCount();
        }

        public void setClientTrueCounts(int[] counts) {
            int len = Math.min(counts.length, BUFFER_VISIBLE);
            System.arraycopy(counts, 0, clientTrueCounts, 0, len);
        }

        @Override
        public int getSlots() {
            return BUFFER_VISIBLE;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= BUFFER_VISIBLE) {
                return ItemStack.EMPTY;
            }
            if (isClient()) {
                return clientCache[slot];
            }
            ItemStack stack = container.getOwner().getBuffer().getStackInSlot(actualSlot(slot));
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            // 显示堆：真实数量超过 maxStackSize 时置 1（原版槽位渲染不画数字），
            // 由 GUI 的 trueCount overlay 绘制真实数量，避免两份数字叠在一起。
            // 必须是副本：原版/自定义路径拿到后可能修改，直接返回活引用会破坏同步语义。
            ItemStack display = stack.copy();
            int maxStack = Math.max(1, stack.getMaxStackSize());
            display.setCount(stack.getCount() > maxStack ? 1 : stack.getCount());
            return display;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || slot < 0 || slot >= BUFFER_VISIBLE || !isItemValidForSlot(slot, stack)) {
                return stack;
            }
            if (isClient()) {
                // 客户端点击预测：基于本地缓存模拟合并，服务端随后推送权威数据纠正。
                // 大数量槽位（真实数量 > maxStackSize）的显示堆恒为 1，预测不改变它，
                // 数字始终由 trueCount overlay 绘制，避免双份数字叠加。
                ItemStack cached = clientCache[slot];
                if (cached.isEmpty()) {
                    if (!simulate) {
                        clientCache[slot] = stack.copy();
                    }
                    return ItemStack.EMPTY;
                }
                if (!new ItemVariant(cached).equals(new ItemVariant(stack))) {
                    return stack;
                }
                if (!simulate) {
                    int maxStack = Math.max(1, cached.getMaxStackSize());
                    if (clientTrueCounts[slot] <= maxStack) {
                        cached.grow(stack.getCount());
                        if (cached.getCount() > maxStack) {
                            cached.setCount(1);
                        }
                    }
                }
                return ItemStack.EMPTY;
            }
            return container.getOwner().getBuffer().insertItem(actualSlot(slot), stack, simulate);
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= BUFFER_VISIBLE || amount <= 0) {
                return ItemStack.EMPTY;
            }
            if (isClient()) {
                ItemStack cached = clientCache[slot];
                if (cached.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                // 大数量槽位显示堆恒为 1：预测取出按显示堆语义返回，不改缓存（同步会纠正）
                boolean bigSlot = clientTrueCounts[slot] > Math.max(1, cached.getMaxStackSize());
                int toExtract = bigSlot ? Math.min(amount, cached.getMaxStackSize())
                        : Math.min(amount, cached.getCount());
                ItemStack result = cached.copy();
                result.setCount(toExtract);
                if (!simulate && !bigSlot) {
                    cached.shrink(toExtract);
                }
                return result;
            }
            ItemStack current = container.getOwner().getBuffer().getStackInSlot(actualSlot(slot));
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            // 单次取出不超过 maxStackSize（可放入玩家背包的量）
            int toExtract = (int) Math.min(amount, Math.min(current.getCount(), Math.max(1, current.getMaxStackSize())));
            return container.getOwner().getBuffer().extractItem(actualSlot(slot), toExtract, simulate);
        }

        /**
         * 把物品退回缓存（shift 取出时背包放不下的剩余部分）。
         */
        public void returnToBuffer(int slot, @Nonnull ItemStack stack) {
            if (isClient() || stack.isEmpty() || slot < 0 || slot >= BUFFER_VISIBLE) {
                return;
            }
            ItemStack remainder = container.getOwner().getBuffer().insertItem(actualSlot(slot), stack, false);
            if (!remainder.isEmpty()) {
                // 原槽已满/被占：退到缓存任意位置
                container.getOwner().getBuffer().insertAnywhere(remainder, false);
            }
            container.getOwner().markDirty();
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            // 客户端：Container 标准同步落点，写入本地缓存。
            if (isClient() && slot >= 0 && slot < BUFFER_VISIBLE) {
                clientCache[slot] = stack;
            }
        }
    }
}

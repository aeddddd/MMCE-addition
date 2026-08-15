package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.SlotItemHandler;

import javax.annotation.Nonnull;

/**
 * 虚拟并行仓容器。
 * <p>
 * 单数据槽 (80, 35) + 标准玩家背包（176x168 布局）。
 * 放入校验：仅接受机器数据；结构成型时仅接受与控制器机器匹配的数据（服务端权威）。
 * 匹配状态由服务端计算并经 window property 同步（客户端 Tile 没有控制器引用，
 * 直接在客户端判断会恒为"不匹配"）。
 */
public class ContainerVirtualParallelHatch extends Container {

    private final TileVirtualParallelHatch owner;

    /** 服务端计算的匹配状态（0/1），window property 同步。 */
    private int matched = 0;
    private int lastSyncedMatched = -1;

    public ContainerVirtualParallelHatch(TileVirtualParallelHatch owner, EntityPlayer player) {
        this.owner = owner;
        DataSlotHandler handler = new DataSlotHandler(owner);

        addSlotToContainer(new SlotItemHandler(handler, 0, 80, 35) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return owner.isDataValid(stack);
            }
        });

        // 玩家背包：起始 (8, 84)
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(new Slot(player.inventory, j + i * 9 + 9, 8 + j * 18, 84 + i * 18));
            }
        }
        // 快捷栏
        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(player.inventory, i, 8 + i * 18, 142));
        }
    }

    public TileVirtualParallelHatch getOwner() {
        return owner;
    }

    /** 客户端读取服务端同步的匹配状态。 */
    public boolean isMatchedSynced() {
        return matched == 1;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        matched = owner.isDataMatched() ? 1 : 0;
        if (lastSyncedMatched != matched) {
            for (IContainerListener listener : listeners) {
                listener.sendWindowProperty(this, 0, matched);
            }
            lastSyncedMatched = matched;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int data) {
        if (id == 0) {
            matched = data;
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return owner.getWorld().getTileEntity(owner.getPos()) == owner &&
                player.getDistanceSq(owner.getPos().add(0.5, 0.5, 0.5)) <= 64.0;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();

        if (index == 0) {
            // 数据槽 → 玩家背包
            if (!mergeItemStack(stack, 1, 37, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 数据槽
            if (!owner.isDataValid(stack) || !mergeItemStack(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
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
     * 数据槽 handler：直接代理 Tile 的单槽存储（两端均为拷贝语义）。
     */
    private static class DataSlotHandler implements IItemHandlerModifiable {

        private final TileVirtualParallelHatch tile;

        DataSlotHandler(TileVirtualParallelHatch tile) {
            this.tile = tile;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? tile.getDataStack() : ItemStack.EMPTY;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            if (slot == 0) {
                tile.setDataStack(stack);
            }
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty() || !tile.isDataValid(stack) || !tile.getDataStack().isEmpty()) {
                return stack;
            }
            if (!simulate) {
                ItemStack placed = stack.copy();
                placed.setCount(1);
                tile.setDataStack(placed);
            }
            ItemStack remainder = stack.copy();
            remainder.shrink(1);
            return remainder;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0 || tile.getDataStack().isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = tile.getDataStack().copy();
            result.setCount(1);
            if (!simulate) {
                tile.setDataStack(ItemStack.EMPTY);
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }
}

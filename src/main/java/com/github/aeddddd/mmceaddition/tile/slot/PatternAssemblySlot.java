package com.github.aeddddd.mmceaddition.tile.slot;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.util.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ME 样板总成中的单个样板槽数据封装。
 * <p>
 * 每个槽位完全独立：拥有独立的输入缓冲、输出缓冲、催化剂槽位。
 * 这是 GTLCore MEPatternBuffer 中 InternalSlot 概念在 MMCE 1.12.2 中的对应实现。
 */
public class PatternAssemblySlot implements IBufferObserver {

    public static final int CATALYST_SLOTS = 12;

    private final int index;
    private final TileMEPatternAssembly owner;

    // 原始样板（玩家编码的 1x 样板）
    @Nullable
    private ICraftingPatternDetails patternDetails;

    // 输入缓冲：AE pushPattern 写入，机器配方消耗
    private final LongItemBuffer inputItemBuffer;
    private final LongFluidBuffer inputFluidBuffer;
    private final LongInputItemHandler inputItemHandler;
    private final LongInputFluidHandler inputFluidHandler;

    // 输出缓冲：机器产物写入，MEAsyncOutputManager 批量注入 AE
    private final LongItemBuffer outputItemBuffer;
    private final LongFluidBuffer outputFluidBuffer;
    private final LongBufferItemHandler outputItemHandler;
    private final LongBufferFluidHandler outputFluidHandler;

    // 催化剂（非消耗品）
    @Nonnull
    private final ItemStack[] catalysts = new ItemStack[CATALYST_SLOTS];

    // 当前是否有配方正在使用该槽（用于 isBusy）
    private boolean active = false;

    public PatternAssemblySlot(int index, TileMEPatternAssembly owner) {
        this.index = index;
        this.owner = owner;

        this.inputItemBuffer = new LongItemBuffer(this);
        this.inputFluidBuffer = new LongFluidBuffer(this);
        this.inputItemHandler = new LongInputItemHandler(this.inputItemBuffer);
        this.inputFluidHandler = new LongInputFluidHandler(this.inputFluidBuffer);

        this.outputItemBuffer = new LongItemBuffer(this);
        this.outputFluidBuffer = new LongFluidBuffer(this);
        this.outputItemHandler = new LongBufferItemHandler(this.outputItemBuffer);
        this.outputFluidHandler = new LongBufferFluidHandler(this.outputFluidBuffer);

        for (int i = 0; i < CATALYST_SLOTS; i++) {
            this.catalysts[i] = ItemStack.EMPTY;
        }
    }

    public int getIndex() {
        return index;
    }

    public TileMEPatternAssembly getOwner() {
        return owner;
    }

    @Nullable
    public ICraftingPatternDetails getPatternDetails() {
        return patternDetails;
    }

    public void setPatternDetails(@Nullable ICraftingPatternDetails patternDetails) {
        this.patternDetails = patternDetails;
    }

    // ==================== 输入缓冲访问 ====================

    public LongItemBuffer getInputItemBuffer() {
        return inputItemBuffer;
    }

    public LongFluidBuffer getInputFluidBuffer() {
        return inputFluidBuffer;
    }

    public LongInputItemHandler getInputItemHandler() {
        return inputItemHandler;
    }

    public LongInputFluidHandler getInputFluidHandler() {
        return inputFluidHandler;
    }

    // ==================== 输出缓冲访问 ====================

    public LongItemBuffer getOutputItemBuffer() {
        return outputItemBuffer;
    }

    public LongFluidBuffer getOutputFluidBuffer() {
        return outputFluidBuffer;
    }

    public LongBufferItemHandler getOutputItemHandler() {
        return outputItemHandler;
    }

    public LongBufferFluidHandler getOutputFluidHandler() {
        return outputFluidHandler;
    }

    // ==================== 催化剂槽 ====================

    @Nonnull
    public ItemStack getCatalyst(int slot) {
        return catalysts[slot];
    }

    public void setCatalyst(int slot, @Nonnull ItemStack stack) {
        this.catalysts[slot] = stack.copy();
    }

    @Nonnull
    public ItemStack[] getCatalysts() {
        return catalysts;
    }

    /**
     * 检查给定物品是否匹配该槽的任意催化剂。
     */
    public boolean matchesCatalyst(@Nonnull ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (ItemStack catalyst : catalysts) {
            if (!catalyst.isEmpty() && ItemStack.areItemStacksEqual(catalyst, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查该槽是否至少有一个非空催化剂。
     */
    public boolean hasAnyCatalyst() {
        for (ItemStack catalyst : catalysts) {
            if (!catalyst.isEmpty()) return true;
        }
        return false;
    }

    // ==================== 激活状态 ====================

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * 该槽是否有有效样板。
     */
    public boolean hasPattern() {
        return patternDetails != null;
    }

    // ==================== pushPattern 写入 ====================

    /**
     * 把 AE pushPattern 的原料写入该槽的输入缓冲。
     *
     * @param inputs 原料数组（AE 的 InventoryCrafting 内容）
     */
    public void pushPattern(@Nonnull ItemStack[] inputs) {
        for (ItemStack stack : inputs) {
            if (stack.isEmpty()) continue;
            // 通过 inputItemHandler 写入，以同步建立槽位-变体映射，
            // 确保 MMCE 的配方检查/消耗能读取到缓冲内容。
            inputItemHandler.insertItem(0, stack, false);
        }
    }

    /**
     * 把流体原料写入该槽的输入缓冲（由 AE2FC 流体假物品解码而来）。
     */
    public void pushPatternFluid(@Nonnull FluidStack[] fluids) {
        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.amount <= 0) continue;
            inputFluidBuffer.fill(fluid, true);
        }
    }

    // ==================== IBufferObserver ====================

    @Override
    public void onBufferNonEmpty() {
        // 输出缓冲非空时通知异步输出管理器
        if (owner != null && !owner.getWorld().isRemote) {
            owner.markOutputDirty(this);
        }
    }

    // ==================== NBT 序列化 ====================

    public void writeToNBT(@Nonnull NBTTagCompound compound) {
        NBTTagCompound inputTag = new NBTTagCompound();
        inputItemBuffer.writeToNBT(inputTag);
        inputFluidBuffer.writeToNBT(inputTag);
        compound.setTag("Input", inputTag);

        NBTTagCompound outputTag = new NBTTagCompound();
        outputItemBuffer.writeToNBT(outputTag);
        outputFluidBuffer.writeToNBT(outputTag);
        compound.setTag("Output", outputTag);

        NBTTagList catalystList = new NBTTagList();
        for (ItemStack catalyst : catalysts) {
            NBTTagCompound tag = new NBTTagCompound();
            catalyst.writeToNBT(tag);
            catalystList.appendTag(tag);
        }
        compound.setTag("Catalysts", catalystList);
    }

    public void readFromNBT(@Nonnull NBTTagCompound compound) {
        if (compound.hasKey("Input")) {
            NBTTagCompound inputTag = compound.getCompoundTag("Input");
            inputItemBuffer.readFromNBT(inputTag);
            inputItemHandler.syncWithBuffer();
            inputFluidBuffer.readFromNBT(inputTag);
        }
        if (compound.hasKey("Output")) {
            NBTTagCompound outputTag = compound.getCompoundTag("Output");
            outputItemBuffer.readFromNBT(outputTag);
            outputFluidBuffer.readFromNBT(outputTag);
        }
        if (compound.hasKey("Catalysts")) {
            NBTTagList catalystList = compound.getTagList("Catalysts", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < catalystList.tagCount() && i < CATALYST_SLOTS; i++) {
                catalysts[i] = new ItemStack(catalystList.getCompoundTagAt(i));
            }
        }
    }
}

package com.github.aeddddd.mmceaddition.virtual;

import github.kasuminova.mmce.common.event.machine.MachineEvent;
import github.kasuminova.mmce.common.event.machine.MachineStructureFormedEvent;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTileNotifiable;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import hellfirepvp.modularmachinery.common.util.IEnergyHandlerAsync;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 虚拟并行仓 TileEntity。
 * <p>
 * 单槽存储机器数据：数据记录的机器与所属控制器机器匹配时，
 * 按数据内数量为控制器提供独立乘区并行度（见 {@link VirtualParallelManager}）。
 * <p>
 * 提供给 MMCE 的组件是一个零容量能源输出组件（能源输出需求几乎不会被配方使用），
 * 保证本方块作为结构组件被注册进控制器的 foundComponents，
 * 同时完全不干扰物品/流体/能源的库存感知并行计算。
 */
public class TileVirtualParallelHatch extends TileEntity implements MachineComponentTileNotifiable {

    /** 零容量能源处理器：不存储、不接收、不输出任何能量。 */
    private static final IEnergyHandlerAsync ZERO_ENERGY_HANDLER = new IEnergyHandlerAsync() {
        @Override
        public long getCurrentEnergy() {
            return 0;
        }

        @Override
        public void setCurrentEnergy(long energy) {
        }

        @Override
        public long getMaxEnergy() {
            return 0;
        }

        @Override
        public boolean extractEnergy(long energy) {
            return false;
        }

        @Override
        public boolean receiveEnergy(long energy) {
            return false;
        }
    };

    /** 槽内机器数据（maxStackSize=1，数量在 NBT 的 count 中）。 */
    @Nonnull
    private ItemStack dataStack = ItemStack.EMPTY;

    /** 结构成型后所属的控制器（用于 GUI 放入校验；并行度计算在控制器侧实时读取）。 */
    @Nullable
    private TileMultiblockMachineController controller;

    @Nonnull
    public ItemStack getDataStack() {
        return dataStack;
    }

    public void setDataStack(@Nonnull ItemStack stack) {
        this.dataStack = stack;
        markDirty();
    }

    /**
     * 校验某物品是否允许放入本仓：必须是机器数据；
     * 结构已成型时要求数据机器与控制器机器匹配。
     */
    public boolean isDataValid(@Nonnull ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemMachineData)) {
            return false;
        }
        String dataMachine = ItemMachineData.getMachineName(stack);
        if (dataMachine == null) {
            return false;
        }
        TileMultiblockMachineController ctrl = controller;
        if (ctrl == null || ctrl.isInvalid()) {
            // 结构未成型：先行接受，不匹配的数据只是不提供并行度
            return true;
        }
        DynamicMachine machine = ctrl.getFoundMachine();
        return machine != null && machine.getRegistryName().toString().equals(dataMachine);
    }

    /**
     * 当前数据是否正为本仓所属机器提供并行度（GUI 状态显示用）。
     */
    public boolean isDataMatched() {
        TileMultiblockMachineController ctrl = controller;
        if (ctrl == null || ctrl.isInvalid() || dataStack.isEmpty()) {
            return false;
        }
        DynamicMachine machine = ctrl.getFoundMachine();
        return machine != null
                && machine.getRegistryName().toString().equals(ItemMachineData.getMachineName(dataStack));
    }

    @Nullable
    public TileMultiblockMachineController getController() {
        return controller;
    }

    @Override
    public void onMachineEvent(MachineEvent event) {
        // 结构成型与结构更新（含仓室补放后重检通过）都刷新控制器引用
        if (event instanceof MachineStructureFormedEvent
                || event instanceof github.kasuminova.mmce.common.event.machine.MachineStructureUpdateEvent) {
            this.controller = event.getController();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        this.controller = null;
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        this.controller = null;
    }

    @Nullable
    @Override
    public MachineComponent<?> provideComponent() {
        return new NeutralEnergyOutputComponent();
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("data")) {
            this.dataStack = new ItemStack(compound.getCompoundTag("data"));
        } else {
            this.dataStack = ItemStack.EMPTY;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (!dataStack.isEmpty()) {
            compound.setTag("data", dataStack.writeToNBT(new NBTTagCompound()));
        }
        return compound;
    }

    /**
     * 中性能源输出组件：零容量，仅用于把本仓注册为结构组件。
     */
    public static class NeutralEnergyOutputComponent extends MachineComponent.EnergyHatch {

        public NeutralEnergyOutputComponent() {
            super(IOType.OUTPUT);
        }

        @Nonnull
        @Override
        public IEnergyHandlerAsync getContainerProvider() {
            return ZERO_ENERGY_HANDLER;
        }
    }
}

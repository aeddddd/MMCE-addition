package com.github.aeddddd.mmceaddition.virtual;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * 虚拟装配台 TileEntity。
 * <p>
 * 单方块机器：把一台 MMCE 多方块机器（控制器 + 全部结构方块，
 * 不含各类仓室、不含蓝图）折算为材料清单，消耗内部缓存槽中的材料
 * 产出/累加"机器数据"。
 * <p>
 * 内部缓存槽：面向极大多方块设计，槽位数量由配置提供（默认 216），
 * 每槽位计数上限为 int（{@link Integer#MAX_VALUE}），允许漏斗/AE 等经
 * ItemHandler Capability 自动填充。
 * <p>
 * 多份材料同时合成时智能合并：缓存槽内材料可装配 k 份时，
 * 一次装配消耗 k 份材料，输出槽已有同机器数据则 count += k。
 */
public class TileVirtualAssembler extends TileEntity {

    /** 输出槽固定索引（独立于缓存槽）。 */
    public static final int OUTPUT_SLOTS = 1;

    private final AssemblerBufferHandler buffer;
    private final OutputSlotHandler output;

    public TileVirtualAssembler() {
        this.buffer = new AssemblerBufferHandler(Math.max(27, MMCEAdditionConfig.virtualAssemblerBufferSlots));
        this.output = new OutputSlotHandler();
    }

    @Nonnull
    public AssemblerBufferHandler getBuffer() {
        return buffer;
    }

    @Nonnull
    public OutputSlotHandler getOutput() {
        return output;
    }

    // ==================== 装配逻辑（服务端权威） ====================

    /**
     * 计算当前缓存槽材料最多可装配的份数。
     * <p>
     * 升级方块参与替换式抵扣：每条升级定义占据一个结构位置，
     * 每份装配最多用 1 个升级方块顶替该位置的基础材料，
     * 因此原料组的有效可用量 = 基础材料 + 该组内升级方块数量。
     *
     * @param machineName 机器注册名；null 或无材料清单时返回 0
     */
    public int getAssembleCount(@Nullable String machineName) {
        DynamicMachine machine = ItemMachineData.resolveMachine(machineName);
        if (machine == null) {
            return 0;
        }
        List<MachineMaterialAnalyzer.IngredientGroup> groups = MachineMaterialAnalyzer.analyze(machine);
        if (groups.isEmpty()) {
            return 0;
        }
        List<MachineMaterialAnalyzer.UpgradeInfo> upgrades = MachineMaterialAnalyzer.upgradesFor(machine);
        // 输出槽剩余空间（int 计数）也是上限之一
        long room = outputRoom(machineName);
        if (room <= 0) {
            return 0;
        }
        long k = Long.MAX_VALUE;
        for (int i = 0; i < groups.size(); i++) {
            MachineMaterialAnalyzer.IngredientGroup group = groups.get(i);
            long base = countAvailable(group);
            long upgradeAvailable = 0;
            int replacements = 0;
            for (MachineMaterialAnalyzer.UpgradeInfo upgrade : upgrades) {
                if (upgrade.getBaseGroupIndex() == i) {
                    replacements++;
                    upgradeAvailable += countAvailableItems(upgrade);
                }
            }
            k = Math.min(k, groupAssembleCount(base, upgradeAvailable, replacements, group.getCount()));
            if (k == 0) {
                return 0;
            }
        }
        return (int) Math.min(k, room);
    }

    /**
     * 单个原料组的可装配份数。
     * 每份需要 count 件物品，其中最多 R 件可由升级方块顶替（每位置每份 1 个）。
     */
    private static long groupAssembleCount(long base, long upgradeAvailable, int replacements, int count) {
        if (replacements == 0 || upgradeAvailable == 0) {
            return base / count;
        }
        if (count <= replacements) {
            // 该组可被升级全覆盖（极端），不构成限制
            return Long.MAX_VALUE / count;
        }
        long boundary = upgradeAvailable / replacements;
        if (base / (count - replacements) < boundary) {
            return base / (count - replacements);
        }
        return (base + upgradeAvailable) / count;
    }

    /**
     * 执行装配：消耗 k 份材料（升级方块优先替换式顶替并记录），产出/合并机器数据。
     *
     * @return 实际装配份数；0 表示失败
     */
    public int assemble(@Nullable String machineName, int k) {
        if (k <= 0) {
            return 0;
        }
        DynamicMachine machine = ItemMachineData.resolveMachine(machineName);
        if (machine == null) {
            return 0;
        }
        List<MachineMaterialAnalyzer.IngredientGroup> groups = MachineMaterialAnalyzer.analyze(machine);
        if (groups.isEmpty()) {
            return 0;
        }
        List<MachineMaterialAnalyzer.UpgradeInfo> upgrades = MachineMaterialAnalyzer.upgradesFor(machine);
        int maxK = getAssembleCount(machineName);
        int actual = Math.min(k, maxK);
        if (actual <= 0) {
            return 0;
        }

        // 消耗材料：先按升级定义替换式顶替（每位置每份 1 个），剩余用基础材料
        java.util.Map<String, Integer> recorded = new java.util.LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            MachineMaterialAnalyzer.IngredientGroup group = groups.get(i);
            long need = (long) group.getCount() * actual;
            for (MachineMaterialAnalyzer.UpgradeInfo upgrade : upgrades) {
                if (upgrade.getBaseGroupIndex() != i || need <= 0) {
                    continue;
                }
                int use = (int) Math.min(actual, countAvailableItems(upgrade));
                if (use > 0) {
                    consumeItems(upgrade, use);
                    recorded.merge(upgrade.getModifierName(), use, Integer::sum);
                    need -= use;
                }
            }
            for (int slot = 0; slot < buffer.getSlots() && need > 0; slot++) {
                ItemStack stack = buffer.stacks[slot];
                if (stack.isEmpty() || !group.accepts(stack)) {
                    continue;
                }
                int take = (int) Math.min(need, stack.getCount());
                stack.shrink(take);
                if (stack.getCount() <= 0) {
                    buffer.stacks[slot] = ItemStack.EMPTY;
                }
                need -= take;
            }
        }

        // 产出/合并机器数据（数量与升级记录都合并）
        if (output.stack.isEmpty()) {
            output.stack = ItemMachineData.createStack(machineName, actual);
        } else {
            long merged = (long) ItemMachineData.getCount(output.stack) + actual;
            ItemMachineData.setCount(output.stack, (int) Math.min(merged, Integer.MAX_VALUE));
        }
        ItemMachineData.addUpgrades(output.stack, recorded);
        // 并行度快照：装配时解析一次写入 NBT，免疫 /mm reload 后自动命名漂移
        if (!recorded.isEmpty()) {
            java.util.Map<String, Integer> snapshot = new java.util.LinkedHashMap<>();
            for (String modifierName : recorded.keySet()) {
                snapshot.put(modifierName,
                        com.github.aeddddd.mmceaddition.parallel.FakeParallelMigrator.parallelismFor(machine, modifierName));
            }
            ItemMachineData.addUpgradeParallelism(output.stack, snapshot);
        }
        markDirty();
        return actual;
    }

    /** 输出槽可容纳的额外数量（同机器数据才可合并）。 */
    private long outputRoom(@Nullable String machineName) {
        if (output.stack.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String existing = ItemMachineData.getMachineName(output.stack);
        if (existing == null || !existing.equals(machineName)) {
            return 0;
        }
        return (long) Integer.MAX_VALUE - ItemMachineData.getCount(output.stack);
    }

    /** 统计缓存槽中可抵扣某原料组的物品总量（long 防溢出）。 */
    private long countAvailable(MachineMaterialAnalyzer.IngredientGroup group) {
        long total = 0;
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack stack = buffer.stacks[slot];
            if (!stack.isEmpty() && group.accepts(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 统计缓存槽中某升级定义的可用方块总量。 */
    private long countAvailableItems(MachineMaterialAnalyzer.UpgradeInfo upgrade) {
        long total = 0;
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack stack = buffer.stacks[slot];
            if (!stack.isEmpty() && upgrade.accepts(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 从缓存槽消耗某升级定义的方块。 */
    private void consumeItems(MachineMaterialAnalyzer.UpgradeInfo upgrade, int amount) {
        long need = amount;
        for (int slot = 0; slot < buffer.getSlots() && need > 0; slot++) {
            ItemStack stack = buffer.stacks[slot];
            if (stack.isEmpty() || !upgrade.accepts(stack)) {
                continue;
            }
            int take = (int) Math.min(need, stack.getCount());
            stack.shrink(take);
            if (stack.getCount() <= 0) {
                buffer.stacks[slot] = ItemStack.EMPTY;
            }
            need -= take;
        }
    }

    // ==================== Capability ====================

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return (T) buffer;
        }
        return super.getCapability(capability, facing);
    }

    // ==================== NBT ====================
    // 注意：ItemStack 原版序列化的 Count 是 byte，int 级数量必须自定义序列化。

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        buffer.deserialize(compound.getTagList("buffer", Constants.NBT.TAG_COMPOUND));
        if (compound.hasKey("output")) {
            output.stack = readStackIntCount(compound.getCompoundTag("output"));
        } else {
            output.stack = ItemStack.EMPTY;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setTag("buffer", buffer.serialize());
        if (!output.stack.isEmpty()) {
            compound.setTag("output", writeStackIntCount(output.stack));
        }
        return compound;
    }

    private static NBTTagCompound writeStackIntCount(ItemStack stack) {
        NBTTagCompound tag = new NBTTagCompound();
        ResourceLocation reg = stack.getItem().getRegistryName();
        tag.setString("id", reg == null ? "minecraft:air" : reg.toString());
        tag.setInteger("Count", stack.getCount());
        tag.setShort("Damage", (short) stack.getMetadata());
        if (stack.hasTagCompound()) {
            tag.setTag("tag", stack.getTagCompound());
        }
        return tag;
    }

    private static ItemStack readStackIntCount(NBTTagCompound tag) {
        Item item = ForgeItemsByName(tag.getString("id"));
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, 1, tag.getShort("Damage"));
        stack.setCount(Math.max(1, tag.getInteger("Count")));
        if (tag.hasKey("tag")) {
            stack.setTagCompound(tag.getCompoundTag("tag"));
        }
        return stack;
    }

    @Nullable
    private static Item ForgeItemsByName(String id) {
        return net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
    }

    // ==================== 缓存槽处理器 ====================

    /**
     * int 上限的缓存槽处理器。
     * 任意物品均可存入（机器数据除外——它不是结构材料，避免误存）。
     */
    public class AssemblerBufferHandler implements IItemHandlerModifiable {

        private ItemStack[] stacks;

        public AssemblerBufferHandler(int slots) {
            this.stacks = new ItemStack[slots];
            java.util.Arrays.fill(this.stacks, ItemStack.EMPTY);
        }

        @Override
        public int getSlots() {
            return stacks.length;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot >= 0 && slot < stacks.length ? stacks[slot] : ItemStack.EMPTY;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            if (slot >= 0 && slot < stacks.length) {
                stacks[slot] = stack;
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || slot < 0 || slot >= stacks.length) {
                return stack;
            }
            // 机器数据不是结构材料，拒绝存入缓存槽
            if (stack.getItem() instanceof ItemMachineData) {
                return stack;
            }
            ItemStack current = stacks[slot];
            if (current.isEmpty()) {
                if (!simulate) {
                    stacks[slot] = stack.copy();
                    markDirtySafe();
                }
                return ItemStack.EMPTY;
            }
            if (!new ItemVariant(current).equals(new ItemVariant(stack))) {
                return stack;
            }
            long room = (long) Integer.MAX_VALUE - current.getCount();
            int accepted = (int) Math.min(room, stack.getCount());
            if (accepted <= 0) {
                return stack;
            }
            if (!simulate) {
                current.grow(accepted);
                markDirtySafe();
            }
            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
        }

        /**
         * 在全缓存范围内插入：先并入已有同类槽位，再占空槽。
         * 供自动化填充与 GUI shift-click 使用。
         */
        @Nonnull
        public ItemStack insertAnywhere(@Nonnull ItemStack stack, boolean simulate) {
            ItemStack remaining = stack;
            // 先合并同类
            for (int i = 0; i < stacks.length && !remaining.isEmpty(); i++) {
                if (!stacks[i].isEmpty()) {
                    remaining = insertItem(i, remaining, simulate);
                }
            }
            // 再占空槽
            for (int i = 0; i < stacks.length && !remaining.isEmpty(); i++) {
                if (stacks[i].isEmpty()) {
                    remaining = insertItem(i, remaining, simulate);
                }
            }
            return remaining;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= stacks.length || amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack current = stacks[slot];
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int extracted = Math.min(amount, current.getCount());
            ItemStack result = current.copy();
            result.setCount(extracted);
            if (!simulate) {
                current.shrink(extracted);
                if (current.getCount() <= 0) {
                    stacks[slot] = ItemStack.EMPTY;
                }
                markDirtySafe();
            }
            return result;
        }

        private void markDirtySafe() {
            markDirty();
        }

        public NBTTagList serialize() {
            NBTTagList list = new NBTTagList();
            for (int i = 0; i < stacks.length; i++) {
                ItemStack stack = stacks[i];
                if (stack.isEmpty()) {
                    continue;
                }
                NBTTagCompound tag = writeStackIntCount(stack);
                tag.setInteger("Slot", i);
                list.appendTag(tag);
            }
            return list;
        }

        public void deserialize(NBTTagList list) {
            int size = Math.max(stacks.length, Math.max(27, MMCEAdditionConfig.virtualAssemblerBufferSlots));
            ItemStack[] next = new ItemStack[size];
            java.util.Arrays.fill(next, ItemStack.EMPTY);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound tag = list.getCompoundTagAt(i);
                int slot = tag.getInteger("Slot");
                if (slot < 0 || slot >= size) {
                    continue;
                }
                next[slot] = readStackIntCount(tag);
            }
            this.stacks = next;
        }
    }

    // ==================== 输出槽处理器 ====================

    /**
     * 输出槽：仅 GUI 使用；只允许取出，不允许放入（机器数据只能由装配产出）。
     */
    public class OutputSlotHandler implements IItemHandlerModifiable {

        @Nonnull
        private ItemStack stack = ItemStack.EMPTY;

        @Nonnull
        public ItemStack getStack() {
            return stack;
        }

        public void setStack(@Nonnull ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int getSlots() {
            return OUTPUT_SLOTS;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? stack : ItemStack.EMPTY;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            if (slot == 0) {
                this.stack = stack;
            }
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || stack.isEmpty() || amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack result = stack.copy();
            result.setCount(1);
            if (!simulate) {
                stack = ItemStack.EMPTY;
                markDirty();
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }
}

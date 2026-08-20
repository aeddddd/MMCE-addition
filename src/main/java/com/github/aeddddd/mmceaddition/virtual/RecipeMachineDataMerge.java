package com.github.aeddddd.mmceaddition.virtual;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;

import javax.annotation.Nonnull;

/**
 * 机器数据合并配方（无形）。
 * <p>
 * 把 2 个及以上相同机器的机器数据放入合成台，合并为一份，
 * 内部存储数量相加（int 上限钳制）。不同机器的数据不能混合。
 * 合成消耗全部输入。
 */
public class RecipeMachineDataMerge extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {

    @Override
    public boolean matches(@Nonnull InventoryCrafting inv, @Nonnull World world) {
        return findMergeTarget(inv) != null;
    }

    @Nonnull
    @Override
    public ItemStack getCraftingResult(@Nonnull InventoryCrafting inv) {
        MergeTarget target = findMergeTarget(inv);
        if (target == null) {
            return ItemStack.EMPTY;
        }
        long sum = 0;
        java.util.Map<String, Integer> upgrades = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> parallelismSnapshot = new java.util.LinkedHashMap<>();
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                sum += ItemMachineData.getCount(stack);
                // 升级记录与并行度快照一并合并（数量相加、快照取最大）
                for (java.util.Map.Entry<String, Integer> entry : ItemMachineData.getUpgrades(stack).entrySet()) {
                    upgrades.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
                for (java.util.Map.Entry<String, Integer> entry : ItemMachineData.getUpgradeParallelismMap(stack).entrySet()) {
                    parallelismSnapshot.merge(entry.getKey(), entry.getValue(), Math::max);
                }
            }
        }
        ItemStack result = ItemMachineData.createStack(target.machineName, (int) Math.min(sum, Integer.MAX_VALUE));
        ItemMachineData.addUpgrades(result, upgrades);
        ItemMachineData.addUpgradeParallelism(result, parallelismSnapshot);
        return result;
    }

    @Override
    public boolean canFit(int width, int height) {
        return width * height >= 2;
    }

    @Nonnull
    @Override
    public ItemStack getRecipeOutput() {
        // 输出是动态的（取决于输入的机器与数量），固定输出为空
        return ItemStack.EMPTY;
    }

    @Nonnull
    @Override
    public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
        // 全部输入都被消耗
        return NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
    }

    /**
     * 校验合成格内容：全部为同一机器的机器数据且数量 ≥2。
     *
     * @return 合法时返回携带机器名的结果，否则 null
     */
    private MergeTarget findMergeTarget(InventoryCrafting inv) {
        String machine = null;
        int pieces = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!(stack.getItem() instanceof ItemMachineData)) {
                return null;
            }
            String name = ItemMachineData.getMachineName(stack);
            if (name == null) {
                return null;
            }
            if (machine == null) {
                machine = name;
            } else if (!machine.equals(name)) {
                return null;
            }
            pieces++;
        }
        return pieces >= 2 ? new MergeTarget(machine) : null;
    }

    private static final class MergeTarget {
        final String machineName;

        MergeTarget(String machineName) {
            this.machineName = machineName;
        }
    }
}

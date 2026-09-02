package com.github.aeddddd.mmceaddition.virtual;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;

import javax.annotation.Nonnull;

/**
 * 机器数据清空升级配方（无形）。
 * <p>
 * 把 1 个带有升级记录的机器数据单独放入合成台，合成一次得到
 * 同机器、同内部存储数量但不含升级记录与并行度快照的副本，
 * 用于清空装配时记录的升级（重新装配前归零）。
 * 无升级记录的机器数据不触发本配方；输入被消耗。
 */
public class RecipeMachineDataClearUpgrades extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {

    @Override
    public boolean matches(@Nonnull InventoryCrafting inv, @Nonnull World world) {
        return findTarget(inv) != null;
    }

    @Nonnull
    @Override
    public ItemStack getCraftingResult(@Nonnull InventoryCrafting inv) {
        ItemStack target = findTarget(inv);
        if (target == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = target.copy();
        result.setCount(1);
        ItemMachineData.clearUpgrades(result);
        return result;
    }

    @Override
    public boolean canFit(int width, int height) {
        return width * height >= 1;
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
     * 校验合成格内容：恰好 1 个带升级记录的机器数据，其余格子为空。
     *
     * @return 合法时返回该机器数据，否则 null
     */
    private ItemStack findTarget(InventoryCrafting inv) {
        ItemStack found = null;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (found != null || !(stack.getItem() instanceof ItemMachineData)) {
                return null;
            }
            found = stack;
        }
        if (found == null || !ItemMachineData.hasUpgrades(found)) {
            return null;
        }
        return found;
    }
}

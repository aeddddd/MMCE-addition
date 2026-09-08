package com.github.aeddddd.mmceaddition.util;

import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;

import java.util.Collections;
import java.util.List;

/**
 * 失败详情记录工具：把配方检查产生的完整错误列表写入控制器缓存。
 * <p>
 * 供 {@code RecipeSearchTaskMixin} / {@code FactoryRecipeSearchTaskMixin} /
 * {@code RecipeThreadMixin} 调用。MMCE 的 {@code CraftingStatus} 只保留第一条错误，
 * 这里保存 {@link RecipeCraftingContext.CraftingCheckResult#getUnlocalizedErrorMessages()}
 * 的不可变副本，展示层通过“状态消息是否仍在列表中”判断缓存是否仍然有效，
 * 因此无需在每个状态切换点手动清除。
 */
public final class FailureDetails {

    private FailureDetails() {
    }

    /**
     * 记录一次失败检查的完整错误列表。result 为 null 或不含错误时清除缓存。
     */
    public static void record(TileMultiblockMachineController controller,
                              RecipeCraftingContext.CraftingCheckResult result) {
        if (!(controller instanceof IFailureDetailsHolder)) {
            return;
        }
        if (result == null) {
            clear(controller);
            return;
        }
        List<String> errors = result.getUnlocalizedErrorMessages();
        if (errors == null || errors.isEmpty()) {
            clear(controller);
            return;
        }
        ((IFailureDetailsHolder) controller).mmceaddition$setFailureDetails(
                Collections.unmodifiableList(errors));
    }

    /**
     * 清除缓存（例如配方搜索成功时）。
     */
    public static void clear(TileMultiblockMachineController controller) {
        if (controller instanceof IFailureDetailsHolder) {
            ((IFailureDetailsHolder) controller).mmceaddition$setFailureDetails(Collections.emptyList());
        }
    }

    /**
     * 读取当前缓存，始终非 null。
     */
    public static List<String> get(TileMultiblockMachineController controller) {
        if (controller instanceof IFailureDetailsHolder) {
            List<String> details = ((IFailureDetailsHolder) controller).mmceaddition$getFailureDetails();
            if (details != null) {
                return details;
            }
        }
        return Collections.emptyList();
    }
}

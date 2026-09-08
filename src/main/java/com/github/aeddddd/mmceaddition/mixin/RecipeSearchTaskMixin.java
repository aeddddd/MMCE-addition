package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.FailureDetails;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Mixin：配方搜索失败时，把最佳匹配配方的完整错误列表缓存到控制器上。
 * <p>
 * MMCE 原生只把 {@code highestValidityResult.getFirstErrorMessage("")} 写入
 * {@code CraftingStatus}，其余错误（例如同时缺能量与缺物品）被丢弃。
 * 本 Mixin 在 {@code computeTask} 返回时截取完整列表，
 * 供 TOP 探针与控制器 GUI 展示。
 * <p>
 * 局部变量表捕获使用 FAILSOFT + require=0：2.2.2/2.3.x 局部变量布局不一致时
 * 静默跳过注入点，退化为 MMCE 原生单行提示，不会崩溃。
 */
@Mixin(value = github.kasuminova.mmce.common.concurrent.RecipeSearchTask.class, remap = false)
public abstract class RecipeSearchTaskMixin {

    @Shadow(remap = false)
    protected TileMultiblockMachineController controller;

    @Inject(
            method = "computeTask()Lhellfirepvp/modularmachinery/common/crafting/helper/RecipeCraftingContext;",
            at = @At("RETURN"),
            locals = LocalCapture.CAPTURE_FAILSOFT,
            require = 0,
            remap = false)
    private void mmceaddition$captureFailureDetails(CallbackInfoReturnable<RecipeCraftingContext> cir,
                                                    DynamicMachine foundMachine,
                                                    MachineRecipe highestValidity,
                                                    RecipeCraftingContext.CraftingCheckResult highestValidityResult,
                                                    float validity) {
        if (cir.getReturnValue() != null) {
            // 搜索成功，旧失败详情作废
            FailureDetails.clear(this.controller);
            return;
        }
        FailureDetails.record(this.controller, highestValidityResult);
    }
}

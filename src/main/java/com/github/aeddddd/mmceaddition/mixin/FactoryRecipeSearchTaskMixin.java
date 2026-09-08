package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.FailureDetails;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.tiles.TileFactoryController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Mixin：{@code FactoryRecipeSearchTask} 覆写了 {@code computeTask}，
 * 父类上的 {@link RecipeSearchTaskMixin} 不会命中，这里单独捕获。
 * 逻辑与 {@link RecipeSearchTaskMixin} 完全一致。
 */
@Mixin(value = github.kasuminova.mmce.common.concurrent.FactoryRecipeSearchTask.class, remap = false)
public abstract class FactoryRecipeSearchTaskMixin {

    @Shadow(remap = false)
    private TileFactoryController factory;

    @Inject(
            method = "computeTask()Lhellfirepvp/modularmachinery/common/crafting/helper/RecipeCraftingContext;",
            at = @At("RETURN"),
            locals = LocalCapture.CAPTURE_FAILSOFT,
            require = 0,
            remap = false)
    private void mmceaddition$captureFailureDetails(CallbackInfoReturnable<RecipeCraftingContext> cir,
                                                    TileFactoryController factory,
                                                    DynamicMachine foundMachine,
                                                    MachineRecipe highestValidity,
                                                    RecipeCraftingContext.CraftingCheckResult highestValidityResult,
                                                    float validity) {
        if (cir.getReturnValue() != null) {
            FailureDetails.clear(this.factory);
            return;
        }
        FailureDetails.record(this.factory, highestValidityResult);
    }
}

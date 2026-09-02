package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.sum.RecipeOutputSummator;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：配方输出加和。
 * <p>
 * 在 {@code finishCrafting(long)}（原生产物输出）完成后，若活动配方属于
 * "所有输入均不消耗"的可加和配方，把同机器其余输入已满足的可加和配方的产物
 * 追加进本次输出（详见 {@link RecipeOutputSummator}）。
 * <p>
 * {@code finishCrafting()V} 委托给 {@code finishCrafting(J)V}，只需挂钩后者；
 * 该方法签名在 2.2.2 与 2.3.x 一致。
 */
@Mixin(value = RecipeCraftingContext.class, remap = false)
public class RecipeCraftingContextSumMixin {

    @Inject(method = "finishCrafting(J)V", at = @At("RETURN"), remap = false)
    private void mmceaddition$sumOutputs(long seed, CallbackInfo ci) {
        RecipeOutputSummator.onFinishCrafting((RecipeCraftingContext) (Object) this);
    }
}

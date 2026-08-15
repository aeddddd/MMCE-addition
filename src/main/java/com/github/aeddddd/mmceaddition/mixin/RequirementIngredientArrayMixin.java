package com.github.aeddddd.mmceaddition.mixin;

import hellfirepvp.modularmachinery.common.crafting.helper.CraftCheck;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementIngredientArray;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.util.ResultChance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 与 {@link RequirementItemMixin} 相同，作用于多物品数组需求（RequirementIngredientArray）。
 * <p>
 * 注意：RequirementCatalyst 覆写了这两个方法走自己的逻辑（可选输入 + modifier 注入），
 * 仅在其调用 super 时经过此处，且催化剂本身 parallelizeUnaffected、parallelism 恒 1，
 * 语义一致，无副作用。
 */
@Mixin(value = RequirementIngredientArray.class, remap = false)
public abstract class RequirementIngredientArrayMixin {

    @Shadow
    public float chance;

    @Shadow
    private int doItemIOInternal(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                 int maxMultiplier, ResultChance chance) {
        throw new AssertionError("Shadow method");
    }

    /** actionType 声明在基类 ComponentRequirement 中，无法 @Shadow，直接转型调用。 */
    private boolean mmceaddition$isNonConsumedInput() {
        return ((RequirementIngredientArray) (Object) this).getActionType() == IOType.INPUT && chance <= 0;
    }

    @Inject(method = "canStartCrafting(Ljava/util/List;Lhellfirepvp/modularmachinery/common/crafting/helper/RecipeCraftingContext;)Lhellfirepvp/modularmachinery/common/crafting/helper/CraftCheck;",
            at = @At("HEAD"), cancellable = true)
    private void mmceaddition$nonConsumedCheck(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                               CallbackInfoReturnable<CraftCheck> cir) {
        if (mmceaddition$isNonConsumedInput()) {
            cir.setReturnValue(doItemIOInternal(components, context, 1, ResultChance.GUARANTEED) >= 1
                    ? CraftCheck.success()
                    : CraftCheck.failure("craftcheck.failure.item.input"));
        }
    }

    @Inject(method = "getMaxParallelism", at = @At("HEAD"), cancellable = true)
    private void mmceaddition$nonConsumedParallelism(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                                     int maxParallelism, CallbackInfoReturnable<Integer> cir) {
        if (mmceaddition$isNonConsumedInput()) {
            cir.setReturnValue(doItemIOInternal(components, context, 1, ResultChance.GUARANTEED) >= 1
                    ? maxParallelism : 0);
        }
    }
}

package com.github.aeddddd.mmceaddition.mixin;

import hellfirepvp.modularmachinery.common.crafting.helper.CraftCheck;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementFluid;
import hellfirepvp.modularmachinery.common.machine.IOType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 与 {@link RequirementItemMixin} 相同，作用于流体需求（RequirementFluid）：
 * 不消耗的流体输入只需存在 1 组即可正确并行。
 */
@Mixin(value = RequirementFluid.class, remap = false)
public abstract class RequirementFluidMixin {

    @Shadow
    public float chance;

    @Shadow
    public IOType actionType;

    @Shadow
    private int doFluidIOInternal(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                  int maxMultiplier) {
        throw new AssertionError("Shadow method");
    }

    @Inject(method = "canStartCrafting", at = @At("HEAD"), cancellable = true)
    private void mmceaddition$nonConsumedCheck(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                               CallbackInfoReturnable<CraftCheck> cir) {
        if (actionType == IOType.INPUT && chance <= 0) {
            cir.setReturnValue(doFluidIOInternal(components, context, 1) >= 1
                    ? CraftCheck.success()
                    : CraftCheck.failure("craftcheck.failure.fluid.input"));
        }
    }

    @Inject(method = "getMaxParallelism", at = @At("HEAD"), cancellable = true)
    private void mmceaddition$nonConsumedParallelism(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                                     int maxParallelism, CallbackInfoReturnable<Integer> cir) {
        if (actionType == IOType.INPUT && chance <= 0) {
            cir.setReturnValue(doFluidIOInternal(components, context, 1) >= 1 ? maxParallelism : 0);
        }
    }
}

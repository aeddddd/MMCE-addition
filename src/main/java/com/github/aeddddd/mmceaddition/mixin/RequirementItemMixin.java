package com.github.aeddddd.mmceaddition.mixin;

import github.kasuminova.mmce.common.helper.AdvancedItemModifier;
import hellfirepvp.modularmachinery.common.crafting.helper.CraftCheck;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementItem;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.util.ResultChance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/**
 * 让"不消耗的物品"（chance &lt;= 0 的输入需求）在并行时只需要 1 组：
 * <ul>
 *   <li>检查阶段（canStartCrafting）按 1 倍验证存在性，而不是按并行度要求 N 份；</li>
 *   <li>并行度计算（getMaxParallelism）只要存在 1 组即不限制并行。</li>
 * </ul>
 * 消耗路径不受影响：chance=0 的需求本来就不会被消耗。
 */
@Mixin(value = RequirementItem.class, remap = false)
public abstract class RequirementItemMixin {

    @Shadow
    public float chance;

    @Shadow
    private int doItemIOInternal(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                 int maxMultiplier, List<AdvancedItemModifier> itemModifiers, ResultChance chance) {
        throw new AssertionError("Shadow method");
    }

    /** actionType 声明在基类 ComponentRequirement 中，无法 @Shadow，直接转型调用。 */
    private boolean mmceaddition$isNonConsumedInput() {
        return ((RequirementItem) (Object) this).getActionType() == IOType.INPUT && chance <= 0;
    }

    // RequirementItem 存在三参重载 canStartCrafting(List, RecipeCraftingContext, List)，
    // 必须用完整描述符只匹配两参的检查入口。
    @Inject(method = "canStartCrafting(Ljava/util/List;Lhellfirepvp/modularmachinery/common/crafting/helper/RecipeCraftingContext;)Lhellfirepvp/modularmachinery/common/crafting/helper/CraftCheck;",
            at = @At("HEAD"), cancellable = true)
    private void mmceaddition$nonConsumedCheck(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                               CallbackInfoReturnable<CraftCheck> cir) {
        if (mmceaddition$isNonConsumedInput()) {
            cir.setReturnValue(doItemIOInternal(components, context, 1, Collections.emptyList(), ResultChance.GUARANTEED) >= 1
                    ? CraftCheck.success()
                    : CraftCheck.failure("craftcheck.failure.item.input"));
        }
    }

    @Inject(method = "getMaxParallelism", at = @At("HEAD"), cancellable = true)
    private void mmceaddition$nonConsumedParallelism(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                                     int maxParallelism, CallbackInfoReturnable<Integer> cir) {
        if (mmceaddition$isNonConsumedInput()) {
            cir.setReturnValue(doItemIOInternal(components, context, 1, Collections.emptyList(), ResultChance.GUARANTEED) >= 1
                    ? maxParallelism : 0);
        }
    }
}

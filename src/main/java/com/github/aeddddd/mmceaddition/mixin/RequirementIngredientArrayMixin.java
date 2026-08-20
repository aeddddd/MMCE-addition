package com.github.aeddddd.mmceaddition.mixin;

import github.kasuminova.mmce.common.itemtype.ChancedIngredientStack;
import hellfirepvp.modularmachinery.common.crafting.helper.CraftCheck;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementCatalyst;
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
 * 识别规则（两点缺一不可）：
 * <ul>
 *   <li><b>排除 RequirementCatalyst</b>：催化剂通过 super 调用本类方法时会经过此处，
 *   但其 chance 字段默认为 0，一旦被当作"chance-0 不消耗输入"拦截，就会用真实组件做 1× 检查——
 *   而催化剂存放在催化剂槽（如样板总成的催化剂槽）而非输入总线，检查必然失败，
 *   getMaxParallelism 因此返回 0，毒化整体并行估算。催化剂自身的覆写已实现正确的
 *   可选语义（找不到则 skip、不限制并行），必须放行；</li>
 *   <li><b>成分级 chance 识别</b>：除需求级 chance &lt;= 0 外，
 *   所有成分（ChancedIngredientStack）chance 均 &lt;= 0 时也视为不消耗。</li>
 * </ul>
 */
@Mixin(value = RequirementIngredientArray.class, remap = false)
public abstract class RequirementIngredientArrayMixin {

    @Shadow
    public float chance;

    @Shadow
    protected final List<ChancedIngredientStack> ingredients = null;

    @Shadow
    private int doItemIOInternal(List<ProcessingComponent<?>> components, RecipeCraftingContext context,
                                 int maxMultiplier, ResultChance chance) {
        throw new AssertionError("Shadow method");
    }

    /**
     * 是否"不消耗的物品输入"。
     * 催化剂（RequirementCatalyst）一律返回 false，交由其自身覆写处理。
     */
    private boolean mmceaddition$isNonConsumedInput() {
        if (((Object) this) instanceof RequirementCatalyst) {
            return false;
        }
        if (((RequirementIngredientArray) (Object) this).getActionType() != IOType.INPUT) {
            return false;
        }
        if (chance <= 0) {
            return true;
        }
        // 成分级 chance：全部成分均不消耗时，整体按不消耗处理
        if (ingredients == null || ingredients.isEmpty()) {
            return false;
        }
        for (ChancedIngredientStack ingredient : ingredients) {
            if (ingredient == null || ingredient.chance > 0) {
                return false;
            }
        }
        return true;
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

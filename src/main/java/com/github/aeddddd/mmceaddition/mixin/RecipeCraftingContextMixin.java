package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import hellfirepvp.modularmachinery.common.crafting.helper.ComponentRequirement;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.helper.RequirementComponents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Mixin：让 {@link TileMEPatternAssembly} 在配方上下文中同时提供输入/输出组件。
 * <p>
 * MMCE 的 {@link hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController}
 * 使用 {@code Map<TileEntity, ProcessingComponent<?>>} 存储组件，单个 Tile 只能对应一个组件。
 * 但 {@link RecipeCraftingContext#updateComponents(java.util.Collection)} 接收的是一个
 * {@code Collection<ProcessingComponent<?>>}，我们在这里把 TileMEPatternAssembly 的单个
 * 物品输入组件展开为“物品输入、物品输出、流体输入、流体输出、能源输入”五个独立组件，
 * 从而让 ME 样板总成同时充当输入仓、输出仓与网络 RF 能源输入口。
 */
@Mixin(value = RecipeCraftingContext.class, remap = false)
public class RecipeCraftingContextMixin {

    @Shadow
    @Final
    private List<RequirementComponents> requirementComponents;

    /**
     * 原生并行分支的门槛要求"全部需求都参与并行计算"，但 MMCE 默认把
     * parallelizeUnaffected 的需求（如催化剂 RequirementCatalyst）排除在外，
     * 导致任何带催化剂的配方永远无法进入原生并行。
     * <p>
     * 这类需求的 getMaxParallelism 自身会按 1 倍验证存在性（存在即返回满并行度），
     * 且不随并行度消耗，因此把它们纳入并行计算是完整且安全的——
     * 这也是催化剂配方（及"物品产流体/流体产物品"等混合配方）能够正确并行的前提。
     */
    @Inject(method = "getAllParallelizableComponents", at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$includeUnaffectedRequirements(CallbackInfoReturnable<List<RequirementComponents>> cir) {
        List<RequirementComponents> list = new ArrayList<>(requirementComponents.size());
        for (RequirementComponents rc : requirementComponents) {
            if (rc.requirement() instanceof ComponentRequirement.Parallelizable) {
                list.add(rc);
            }
        }
        cir.setReturnValue(list);
    }

    @ModifyVariable(
            method = "updateComponents",
            at = @At("HEAD"),
            argsOnly = true,
            remap = false
    )
    private Collection<ProcessingComponent<?>> expandPatternAssemblyComponents(Collection<ProcessingComponent<?>> components) {
        List<ProcessingComponent<?>> result = null;

        for (ProcessingComponent<?> pc : components) {
            if (pc.getComponent() instanceof TileMEPatternAssembly.PatternAssemblyItemInputBus) {
                TileMEPatternAssembly assembly = ((TileMEPatternAssembly.PatternAssemblyItemInputBus) pc.getComponent()).getAssembly();
                if (result == null) {
                    result = new ArrayList<>(components);
                }
                // 移除原来的单个输入组件，替换为五个独立组件（含网络 RF 能源输入）
                result.remove(pc);
                result.add(assembly.createItemInputProcessingComponent());
                result.add(assembly.createItemOutputProcessingComponent());
                result.add(assembly.createFluidInputProcessingComponent());
                result.add(assembly.createFluidOutputProcessingComponent());
                result.add(assembly.createEnergyInputProcessingComponent());
            }
        }

        return result == null ? components : result;
    }
}

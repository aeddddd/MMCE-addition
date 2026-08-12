package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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

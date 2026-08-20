package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.tile.TileInputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEOutputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import hellfirepvp.modularmachinery.common.crafting.helper.ComponentRequirement;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.helper.RequirementComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Mixin：让 {@link TileMEPatternAssembly} 与 {@link TileMEOutputAssembly} 在配方上下文中
 * 同时提供多个输入/输出组件。
 * <p>
 * MMCE 的控制器使用 {@code Map<TileEntity, ProcessingComponent<?>>} 存储组件，
 * 单个 Tile 只能对应一个组件。我们在上下文装配组件时：
 * <ul>
 *   <li>把样板总成的单个物品输入组件展开为"物品输入、物品输出、流体输入、流体输出、
 *       能源输入"五个独立组件；</li>
 *   <li>把输出总成仓的标记组件展开为"物品输出、流体输出、能源输入、能源输出"
 *       四个独立组件。</li>
 * </ul>
 * <p>
 * 版本兼容（编译期对齐 2.2.2，运行期同时支持 2.3.x）：
 * <ul>
 *   <li>2.2.2：{@code updateComponents(Collection)} —— 见
 *       {@link #mmceaddition$expandComponentsLegacy}；</li>
 *   <li>2.3.x：{@code updateComponents(Map)} 且组件按组分桶 —— 见
 *       {@link #mmceaddition$expandComponentsGrouped}（反射读取 typeComponents）；</li>
 *   <li>两个处理器都用 {@code require = 0} 并限定完整方法描述符，
 *       目标不存在时静默跳过，互不干扰。</li>
 * </ul>
 */
@Mixin(value = RecipeCraftingContext.class, remap = false)
public class RecipeCraftingContextMixin {

    // ==================== 并行门槛（两个版本通用） ====================

    /**
     * 原生并行分支的门槛要求"全部需求都参与并行计算"，但 MMCE 默认把
     * parallelizeUnaffected 的需求（如催化剂 RequirementCatalyst）排除在外，
     * 导致任何带催化剂的配方永远无法进入原生并行。
     * <p>
     * 这类需求的 getMaxParallelism 自身会按 1 倍验证存在性（存在即返回满并行度），
     * 且不随并行度消耗，因此把它们纳入并行计算是完整且安全的——
     * 这也是催化剂配方（及"物品产流体/流体产物品"等混合配方）能够正确并行的前提。
     * <p>
     * 组件列表来源做版本适配：2.3.x 有 getCurrentComponents()，
     * 2.2.2 只有 List 类型的 requirementComponents 字段（2.3.x 该字段已变为按组 Map）。
     */
    @Inject(method = "getAllParallelizableComponents", at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$includeUnaffectedRequirements(CallbackInfoReturnable<Collection<RequirementComponents>> cir) {
        List<RequirementComponents> all = mmceaddition$currentComponents();
        if (all == null) {
            return;
        }
        List<RequirementComponents> list = new ArrayList<>(all.size());
        for (RequirementComponents rc : all) {
            if (rc.requirement() instanceof ComponentRequirement.Parallelizable) {
                list.add(rc);
            }
        }
        cir.setReturnValue(list);
    }

    private static Method mmceaddition$mGetCurrentComponents;
    private static Field mmceaddition$fRequirementComponents;
    private static boolean mmceaddition$srcInit;

    @SuppressWarnings("unchecked")
    private List<RequirementComponents> mmceaddition$currentComponents() {
        try {
            if (!mmceaddition$srcInit) {
                mmceaddition$srcInit = true;
                Class<?> c = RecipeCraftingContext.class;
                try {
                    mmceaddition$mGetCurrentComponents = c.getMethod("getCurrentComponents");
                } catch (NoSuchMethodException e) {
                    mmceaddition$fRequirementComponents = c.getDeclaredField("requirementComponents");
                    mmceaddition$fRequirementComponents.setAccessible(true);
                }
            }
            if (mmceaddition$mGetCurrentComponents != null) {
                return (List<RequirementComponents>) mmceaddition$mGetCurrentComponents.invoke(this);
            }
            return (List<RequirementComponents>) mmceaddition$fRequirementComponents.get(this);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 五组件展开：2.2.2（Collection 签名） ====================

    @ModifyVariable(
            method = "updateComponents(Ljava/util/Collection;)V",
            at = @At("HEAD"),
            argsOnly = true,
            remap = false,
            require = 0
    )
    private Collection<ProcessingComponent<?>> mmceaddition$expandComponentsLegacy(Collection<ProcessingComponent<?>> components) {
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
            } else if (pc.getComponent() instanceof TileMEOutputAssembly.OutputAssemblyItemOutputBus) {
                TileMEOutputAssembly assembly = ((TileMEOutputAssembly.OutputAssemblyItemOutputBus) pc.getComponent()).getAssembly();
                if (result == null) {
                    result = new ArrayList<>(components);
                }
                // 移除标记组件，展开为物品输出、流体输出、能源输入、能源输出四个独立组件
                result.remove(pc);
                result.add(assembly.createItemOutputProcessingComponent());
                result.add(assembly.createFluidOutputProcessingComponent());
                result.add(assembly.createEnergyInputProcessingComponent());
                result.add(assembly.createEnergyOutputProcessingComponent());
            } else if (pc.getComponent() instanceof TileInputAssembly.InputAssemblyItemInputBus) {
                TileInputAssembly assembly = ((TileInputAssembly.InputAssemblyItemInputBus) pc.getComponent()).getAssembly();
                if (result == null) {
                    result = new ArrayList<>(components);
                }
                // 移除标记组件，展开为物品输入、流体输入两个独立组件
                result.remove(pc);
                result.add(assembly.createItemInputProcessingComponent());
                result.add(assembly.createFluidInputProcessingComponent());
            }
        }

        return result == null ? components : result;
    }

    // ==================== 五组件展开：2.3.x（Map 签名 + 分组组件表） ====================

    /**
     * 2.3.x 的 updateComponents 接收按组分桶的 Map，并物化成私有字段
     * {@code typeComponents: Map<Long, Collection<ProcessingComponent<?>>>}。
     * 单个 Tile 在桶里仍然只有一个组件，这里把其余组件反射追加进同一个桶
     * （保留原标记组件；幂等，重复调用不产生重复项）。
     * <p>
     * 注入点必须是 {@code updateRequirementComponents} 的 HEAD 而不是
     * {@code updateComponents} 的 TAIL：2.3.x 的 updateComponents 在 return 前
     * 就会调用 updateRequirementComponents 把 typeComponents 物化成
     * requirementComponents，TAIL 处再改 typeComponents 已来不及，
     * 需求侧永远看不到展开后的组件（实测失效）。
     * updateRequirementComponents 也会被其他路径调用，故处理器保持幂等。
     */
    @Inject(method = "updateRequirementComponents()V", at = @At("HEAD"), remap = false, require = 0)
    private void mmceaddition$expandComponentsGrouped(CallbackInfo ci) {
        try {
            Field f = RecipeCraftingContext.class.getDeclaredField("typeComponents");
            f.setAccessible(true);
            Object tc = f.get(this);
            if (!(tc instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> groups = (Map<Object, Object>) tc;
            for (Map.Entry<Object, Object> entry : groups.entrySet()) {
                if (!(entry.getValue() instanceof Collection)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Collection<ProcessingComponent<?>> group = (Collection<ProcessingComponent<?>>) entry.getValue();
                List<ProcessingComponent<?>> expanded = null;
                for (ProcessingComponent<?> pc : group) {
                    if (pc.getComponent() instanceof TileMEPatternAssembly.PatternAssemblyItemInputBus) {
                        TileMEPatternAssembly assembly =
                                ((TileMEPatternAssembly.PatternAssemblyItemInputBus) pc.getComponent()).getAssembly();
                        if (expanded == null) {
                            expanded = new ArrayList<>(group);
                        }
                        if (mmceaddition$alreadyExpanded(expanded, assembly)) {
                            continue;
                        }
                        expanded.add(assembly.createItemOutputProcessingComponent());
                        expanded.add(assembly.createFluidInputProcessingComponent());
                        expanded.add(assembly.createFluidOutputProcessingComponent());
                        expanded.add(assembly.createEnergyInputProcessingComponent());
                    } else if (pc.getComponent() instanceof TileMEOutputAssembly.OutputAssemblyItemOutputBus) {
                        TileMEOutputAssembly assembly =
                                ((TileMEOutputAssembly.OutputAssemblyItemOutputBus) pc.getComponent()).getAssembly();
                        if (expanded == null) {
                            expanded = new ArrayList<>(group);
                        }
                        if (mmceaddition$alreadyExpandedOutput(expanded, assembly)) {
                            continue;
                        }
                        // 标记组件本身就是物品输出组件，保留在桶中；追加其余三个组件
                        expanded.add(assembly.createFluidOutputProcessingComponent());
                        expanded.add(assembly.createEnergyInputProcessingComponent());
                        expanded.add(assembly.createEnergyOutputProcessingComponent());
                    } else if (pc.getComponent() instanceof TileInputAssembly.InputAssemblyItemInputBus) {
                        TileInputAssembly assembly =
                                ((TileInputAssembly.InputAssemblyItemInputBus) pc.getComponent()).getAssembly();
                        if (expanded == null) {
                            expanded = new ArrayList<>(group);
                        }
                        if (mmceaddition$alreadyExpandedInput(expanded, assembly)) {
                            continue;
                        }
                        // 标记组件本身就是物品输入组件，保留在桶中；追加流体输入组件
                        expanded.add(assembly.createFluidInputProcessingComponent());
                    }
                }
                if (expanded != null) {
                    groups.put(entry.getKey(), expanded);
                }
            }
        } catch (Throwable ignored) {
            // 反射失败 = 非 2.3.x 结构，legacy 处理器已覆盖
        }
    }

    private boolean mmceaddition$alreadyExpanded(List<ProcessingComponent<?>> list, TileMEPatternAssembly assembly) {
        for (ProcessingComponent<?> q : list) {
            if (q.getComponent() instanceof TileMEPatternAssembly.PatternAssemblyItemOutputBus
                    && ((TileMEPatternAssembly.PatternAssemblyItemOutputBus) q.getComponent()).getAssembly() == assembly) {
                return true;
            }
        }
        return false;
    }

    private boolean mmceaddition$alreadyExpandedOutput(List<ProcessingComponent<?>> list, TileMEOutputAssembly assembly) {
        for (ProcessingComponent<?> q : list) {
            if (q.getComponent() instanceof TileMEOutputAssembly.OutputAssemblyFluidOutputHatch
                    && ((TileMEOutputAssembly.OutputAssemblyFluidOutputHatch) q.getComponent()).getAssembly() == assembly) {
                return true;
            }
        }
        return false;
    }

    private boolean mmceaddition$alreadyExpandedInput(List<ProcessingComponent<?>> list, TileInputAssembly assembly) {
        for (ProcessingComponent<?> q : list) {
            if (q.getComponent() instanceof TileInputAssembly.InputAssemblyFluidInputHatch
                    && ((TileInputAssembly.InputAssemblyFluidInputHatch) q.getComponent()).getAssembly() == assembly) {
                return true;
            }
        }
        return false;
    }
}

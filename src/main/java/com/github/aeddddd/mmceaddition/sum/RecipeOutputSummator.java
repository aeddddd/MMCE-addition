package com.github.aeddddd.mmceaddition.sum;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import github.kasuminova.mmce.common.itemtype.ChancedIngredientStack;
import hellfirepvp.modularmachinery.common.crafting.ActiveMachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.helper.ComponentRequirement;
import hellfirepvp.modularmachinery.common.crafting.helper.CraftCheck;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementCatalyst;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementFluid;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementIngredientArray;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementItem;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.util.ResultChance;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 配方输出加和器。
 * <p>
 * 针对"所有输入均为不消耗（chance &lt;= 0 的存在性需求）"的配方：这类配方的前提条件可以
 * 同时成立，但 MMCE 一台机器同一时刻只有一个活动配方，导致每种不消耗材料都得各做一台机器。
 * <p>
 * 本类在活动配方完成（{@code RecipeCraftingContext.finishCrafting(long)}）时：
 * <ol>
 *   <li>确认活动配方本身是"可加和配方"（见 {@link #isSummableRecipe}）；</li>
 *   <li>遍历同机器其余可加和配方，凡其不消耗输入在当前仓室中全部存在的，
 *       把它的产物按当前上下文 modifier × 并行度追加产出；</li>
 *   <li>每个输出需求先在组件副本上模拟插入做空间检查，空间不足则跳过该产物（不 void）。</li>
 * </ol>
 * 判定与产出均不触碰共享的 {@link MachineRecipe} 需求对象（全部 deepCopy 后操作），
 * 不存在异步搜索线程的并发问题。
 */
public final class RecipeOutputSummator {

    private static final Random RAND = new Random();
    /** 机器 → 可加和配方列表（懒计算，reload 时清空） */
    private static final Map<DynamicMachine, List<MachineRecipe>> CACHE = new HashMap<>();

    private RecipeOutputSummator() {
    }

    /** 机器注册/重载后调用：清空缓存，下次 finishCrafting 时按最新配方重新计算 */
    public static synchronized void onMachinesRegistered() {
        CACHE.clear();
    }

    /**
     * finishCrafting(long) 完成后调用：为活动配方追加其余可加和配方的产物。
     */
    public static void onFinishCrafting(RecipeCraftingContext ctx) {
        if (!MMCEAdditionConfig.enableRecipeOutputSummation) {
            return;
        }
        try {
            ActiveMachineRecipe active = ctx.getActiveRecipe();
            if (active == null) {
                return;
            }
            MachineRecipe activeRecipe = active.getRecipe();
            DynamicMachine machine = activeRecipe.getOwningMachine();
            if (machine == null || isBlacklisted(machine)) {
                return;
            }
            List<MachineRecipe> summable = getSummableRecipes(machine);
            if (summable.size() < 2 || !summable.contains(activeRecipe)) {
                return;
            }
            int parallelism = Math.max(1, active.getParallelism());
            for (MachineRecipe other : summable) {
                if (other == activeRecipe) {
                    continue;
                }
                try {
                    if (!inputsPresent(other, ctx)) {
                        continue;
                    }
                    applyOutputs(other, ctx, parallelism, new ResultChance(RAND.nextLong()));
                    if (MMCEAdditionConfig.debugRecipeSummation) {
                        MMCEAddition.LOGGER.info("配方加和: {} 附加产出 {}（并行 x{}）",
                                activeRecipe.getRegistryName(), other.getRegistryName(), parallelism);
                    }
                } catch (Throwable t) {
                    MMCEAddition.LOGGER.error("配方加和: 追加 {} 的产物失败", other.getRegistryName(), t);
                }
            }
        } catch (Throwable t) {
            MMCEAddition.LOGGER.error("配方加和: 执行失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 可加和配方判定与缓存
    // ------------------------------------------------------------------

    private static synchronized List<MachineRecipe> getSummableRecipes(DynamicMachine machine) {
        List<MachineRecipe> cached = CACHE.get(machine);
        if (cached != null) {
            return cached;
        }
        List<MachineRecipe> result = new ArrayList<>();
        for (MachineRecipe recipe : machine.getAvailableRecipes()) {
            if (isSummableRecipe(recipe)) {
                result.add(recipe);
            }
        }
        if (result.isEmpty()) {
            result = Collections.emptyList();
        }
        CACHE.put(machine, result);
        if (MMCEAdditionConfig.debugRecipeSummation) {
            MMCEAddition.LOGGER.info("配方加和: 机器 {} 识别到 {} 个可加和配方",
                    machine.getRegistryName(), result.size());
        }
        return result;
    }

    /**
     * "可加和配方"判定：
     * <ul>
     *   <li>至少 1 个输入、1 个输出；</li>
     *   <li>所有输入均为不消耗的物品/流体需求（chance &lt;= 0，数组需求允许成分级 chance 全 &lt;= 0）；</li>
     *   <li>不含催化剂需求（RequirementCatalyst 的 modifier 注入语义无法简单加和，直接排除）；</li>
     *   <li>不含任何 PerTick 需求（能量输入会真实消耗、能量/流体 per-tick 输出走 ioTick 管线，
     *       均不属于"完成时产物加和"的语义范围）。</li>
     * </ul>
     */
    private static boolean isSummableRecipe(MachineRecipe recipe) {
        int inputs = 0;
        int outputs = 0;
        for (ComponentRequirement<?, ?> req : recipe.getCraftingRequirements()) {
            if (req instanceof ComponentRequirement.PerTick) {
                return false;
            }
            if (req instanceof RequirementCatalyst) {
                return false;
            }
            if (req.getActionType() == IOType.INPUT) {
                if (!isNonConsumedInput(req)) {
                    return false;
                }
                inputs++;
            } else {
                outputs++;
            }
        }
        return inputs > 0 && outputs > 0;
    }

    /** 是否"不消耗的物品/流体输入"（与三个 Requirement Mixin 的识别规则一致） */
    private static boolean isNonConsumedInput(ComponentRequirement<?, ?> req) {
        if (req instanceof RequirementItem) {
            return ((RequirementItem) req).chance <= 0;
        }
        if (req instanceof RequirementFluid) {
            return ((RequirementFluid) req).chance <= 0;
        }
        if (req instanceof RequirementIngredientArray) {
            RequirementIngredientArray arr = (RequirementIngredientArray) req;
            if (arr.chance <= 0) {
                return true;
            }
            List<ChancedIngredientStack> ingredients = reflectIngredients(arr);
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
        return false;
    }

    // ------------------------------------------------------------------
    // 输入存在性检查
    // ------------------------------------------------------------------

    /**
     * 检查 other 配方的全部不消耗输入是否存在于当前机器仓室中。
     * 在组件副本上做 1x 存在性检查（命中本模组的 Requirement Mixin 不消耗分支），不改动真实库存。
     */
    private static boolean inputsPresent(MachineRecipe other, RecipeCraftingContext ctx) {
        for (ComponentRequirement<?, ?> req : other.getCraftingRequirements()) {
            if (req.getActionType() != IOType.INPUT) {
                continue;
            }
            List<ProcessingComponent<?>> comps = findValidComponents(ctx, req);
            if (comps.isEmpty()) {
                return false;
            }
            ComponentRequirement<?, ?> copy = req.deepCopy().postDeepCopy(req);
            if (copy instanceof ComponentRequirement.MultiComponent) {
                ComponentRequirement.MultiComponent mc = (ComponentRequirement.MultiComponent) copy;
                CraftCheck result = mc.canStartCrafting(mc.copyComponents(comps), ctx);
                if (!result.isSuccess()) {
                    return false;
                }
            } else {
                boolean ok = false;
                for (ProcessingComponent<?> pc : comps) {
                    if (copy.canStartCrafting(pc, ctx, Collections.emptyList()).isSuccess()) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 输出加和
    // ------------------------------------------------------------------

    /**
     * 把 other 配方的全部输出需求按当前上下文 modifier × 并行度追加产出。
     * 每个输出需求先在组件副本上模拟插入做空间检查，空间不足则跳过该产物（不 void）。
     */
    private static void applyOutputs(MachineRecipe other, RecipeCraftingContext ctx,
                                     int parallelism, ResultChance chance) {
        for (ComponentRequirement<?, ?> req : other.getCraftingRequirements()) {
            if (req.getActionType() != IOType.OUTPUT) {
                continue;
            }
            List<ProcessingComponent<?>> comps = findValidComponents(ctx, req);
            if (comps.isEmpty()) {
                continue;
            }
            // 与原生 updateRequirementComponents 相同的缩放路径：上下文 modifier + setParallelism
            ComponentRequirement<?, ?> scaled = req.deepCopyModified(ctx.getModifiers(req.getRequirementType()))
                    .postDeepCopy(req);
            if (scaled instanceof ComponentRequirement.Parallelizable) {
                ((ComponentRequirement.Parallelizable) scaled).setParallelism(parallelism);
            }
            if (scaled instanceof ComponentRequirement.MultiComponent) {
                ComponentRequirement.MultiComponent mc = (ComponentRequirement.MultiComponent) scaled;
                CraftCheck check = mc.canStartCrafting(mc.copyComponents(comps), ctx);
                if (!check.isSuccess()) {
                    continue;
                }
                mc.finishCrafting(comps, ctx, chance);
            } else {
                for (ProcessingComponent<?> pc : comps) {
                    if (scaled.canStartCrafting(pc, ctx, Collections.emptyList()).isSuccess()) {
                        scaled.finishCrafting(pc, ctx, chance);
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 组件查找（兼容 2.2.2 Collection 与 2.3.x 分组 Map 两种 typeComponents 结构）
    // ------------------------------------------------------------------

    private static Field fTypeComponents;
    private static Field fIngredients;
    /**
     * 反射是否已初始化。volatile：本方法可能在 AE 合成线程被首次触发，
     * 而字段随后要在服务端主线程读取，必须保证可见性。
     * <p>
     * 注意赋值顺序：先写完两个 {@link Field}，最后才置位本标志。
     * volatile 写是释放屏障，读方看到 {@code true} 时必然也能看到字段赋值，
     * 否则会出现"以为初始化完成、实际字段还是 null"而静默跳过加和的情况。
     */
    private static volatile boolean reflectInit;

    private static List<ProcessingComponent<?>> findValidComponents(RecipeCraftingContext ctx,
                                                                    ComponentRequirement<?, ?> req) {
        List<ProcessingComponent<?>> all = allComponents(ctx);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProcessingComponent<?>> result = new ArrayList<>();
        for (ProcessingComponent<?> pc : all) {
            if (!req.isValidComponent(pc, ctx)) {
                continue;
            }
            if (req.getTag() != null && !req.getTag().equals(pc.getTag())) {
                continue;
            }
            result.add(pc);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<ProcessingComponent<?>> allComponents(RecipeCraftingContext ctx) {
        try {
            initReflection();
            if (fTypeComponents == null) {
                return Collections.emptyList();
            }
            Object v = fTypeComponents.get(ctx);
            List<ProcessingComponent<?>> out = new ArrayList<>();
            if (v instanceof Map) {
                // 2.3.x：Map<Long, Collection<ProcessingComponent<?>>>（按组分桶）
                for (Object c : ((Map<?, ?>) v).values()) {
                    if (c instanceof Collection) {
                        out.addAll((Collection<ProcessingComponent<?>>) c);
                    }
                }
            } else if (v instanceof Collection) {
                // 2.2.2：Collection<ProcessingComponent<?>>
                out.addAll((Collection<ProcessingComponent<?>>) v);
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ChancedIngredientStack> reflectIngredients(RequirementIngredientArray arr) {
        try {
            initReflection();
            if (fIngredients == null) {
                return null;
            }
            return (List<ChancedIngredientStack>) fIngredients.get(arr);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void initReflection() {
        if (reflectInit) {
            return;
        }
        try {
            fTypeComponents = RecipeCraftingContext.class.getDeclaredField("typeComponents");
            fTypeComponents.setAccessible(true);
        } catch (Throwable ignored) {
            fTypeComponents = null;
        }
        try {
            fIngredients = RequirementIngredientArray.class.getDeclaredField("ingredients");
            fIngredients.setAccessible(true);
        } catch (Throwable ignored) {
            fIngredients = null;
        }
        // 必须最后置位：volatile 写是释放屏障，保证读到 true 的线程也能看到上面两个字段的赋值。
        reflectInit = true;
    }

    private static boolean isBlacklisted(DynamicMachine machine) {
        String fullName = machine.getRegistryName().toString();
        String path = machine.getRegistryName().getPath();
        for (String entry : MMCEAdditionConfig.recipeSummationMachineBlacklist) {
            if (entry.equals(fullName) || entry.equals(path)) {
                return true;
            }
        }
        return false;
    }
}

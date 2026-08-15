package com.github.aeddddd.mmceaddition.parallel;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.requirement.type.RequirementType;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.modifier.AbstractModifierReplacement;
import hellfirepvp.modularmachinery.common.modifier.MultiBlockModifierReplacement;
import hellfirepvp.modularmachinery.common.modifier.RecipeModifier;
import hellfirepvp.modularmachinery.common.modifier.SingleBlockModifierReplacement;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伪并行迁移器。
 * <p>
 * 部分整合包用机器 JSON 中“同一 target 的 input ×N + output ×N”modifier 组来模拟并行
 * （消耗与产出同时放大 N 倍、耗时不变）。这种写法的缺陷：
 * 凑不齐 N 倍材料时配方完全无法启动、能量等 per-tick 消耗不会被放大、
 * 与 AE 按 1 倍推送样板的供料方式冲突。
 * <p>
 * 本类在 MMCE 机器注册完成时（由 {@code MachineRegistryMixin} 触发）扫描所有机器，
 * 把上述 modifier 组从机器对象中摘除，记录为“元件 → 并行度”的授权表（Grant），
 * 并打开机器的原生并行开关。运行时由 {@code ParallelismControllerMixin}
 * 覆写 {@code getMaxParallelism()}，按当前结构中实际安装的元件动态给出并行度，
 * 从而实现“升级结构方块即提升并行”的真实并行，全程无需修改任何 JSON。
 */
public final class FakeParallelMigrator {

    /**
     * 一条迁移授权：结构中名为 {@link #modifierName} 的元件匹配成功时，提供 {@link #parallelism} 并行度。
     */
    public static final class Grant {
        public final String modifierName;
        public final int parallelism;

        public Grant(String modifierName, int parallelism) {
            this.modifierName = modifierName;
            this.parallelism = parallelism;
        }
    }

    /** 并行度聚合策略（多档元件同时存在时如何合并）。 */
    private enum Strategy {
        /** 取最高档（替代式升级，默认）。 */
        MAX,
        /** 累加（叠加式元件）。 */
        SUM,
        /** 连乘（与原 modifier 连乘语义完全等价，极易爆炸，需配合上限）。 */
        PRODUCT
    }

    /** 单组倍率的合理性上限，超过视为异常数据，拒绝转换。 */
    private static final int MAX_REASONABLE_PARALLEL = 4096;

    /** 机器注册名 → 该机器的授权表。只读场景使用 ConcurrentHashMap 保证异步配方搜索线程可见。 */
    private static final Map<String, List<Grant>> GRANTS = new ConcurrentHashMap<>();

    /** 已完成迁移扫描的机器对象（按身份），保证 /mm reload 重注册时幂等。 */
    private static final Set<DynamicMachine> MIGRATED = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * 已成功强制打开配方并行开关的机器对象（按身份）。
     * MMCE 先注册机器、后加载配方（CommonProxy 中 registerMachines 早于 loadRecipeRegistry），
     * 迁移时机器的配方表还是空的，因此配方开关必须在配方加载完成后懒强制。
     */
    private static final Set<DynamicMachine> FORCED_RECIPES = Collections.newSetFromMap(new IdentityHashMap<>());

    /** AbstractModifierReplacement#modifier 的反射句柄（final 字段，用于整体替换列表）。 */
    private static Field modifierListField;

    /** MachineRecipe#parallelized 的反射句柄（MMCE 未提供 setter）。 */
    private static Field recipeParallelizedField;

    private FakeParallelMigrator() {
    }

    /**
     * 对一批刚注册的机器执行伪并行迁移。对每个机器对象只执行一次。
     */
    public static void migrateMachines(Collection<DynamicMachine> machines) {
        if (machines == null || machines.isEmpty()) {
            return;
        }
        synchronized (MIGRATED) {
            for (DynamicMachine machine : machines) {
                if (machine == null || !MIGRATED.add(machine)) {
                    continue;
                }
                try {
                    migrateMachine(machine);
                } catch (Exception e) {
                    MMCEAddition.LOGGER.error("伪并行迁移失败: {}", machine.getRegistryName(), e);
                }
            }
        }
    }

    /**
     * 该机器是否存在已迁移的伪并行授权。
     */
    public static boolean hasGrants(DynamicMachine machine) {
        List<Grant> grants = GRANTS.get(machine.getRegistryName().toString());
        return grants != null && !grants.isEmpty();
    }

    /**
     * 按当前结构中实际匹配的元件，计算控制器此刻的有效并行度。
     * <p>
     * 只读取控制器内存中的 foundModifiers（ConcurrentHashMap），
     * 不访问世界，可在主线程或异步配方搜索线程安全调用。
     */
    public static int computeForController(TileMultiblockMachineController controller) {
        DynamicMachine machine = controller.getFoundMachine();
        if (machine == null) {
            return 1;
        }
        List<Grant> grants = GRANTS.get(machine.getRegistryName().toString());
        if (grants == null || grants.isEmpty()) {
            return 1;
        }
        switch (strategy()) {
            case SUM: {
                int sum = 0;
                for (Grant g : grants) {
                    if (controller.hasModifierReplacement(g.modifierName)) {
                        sum += g.parallelism;
                    }
                }
                return Math.max(1, Math.min(sum, MAX_REASONABLE_PARALLEL));
            }
            case PRODUCT: {
                long product = 1;
                boolean any = false;
                for (Grant g : grants) {
                    if (controller.hasModifierReplacement(g.modifierName)) {
                        product *= g.parallelism;
                        any = true;
                    }
                }
                return any ? (int) Math.min(product, MAX_REASONABLE_PARALLEL) : 1;
            }
            case MAX:
            default: {
                int max = 1;
                for (Grant g : grants) {
                    if (controller.hasModifierReplacement(g.modifierName)) {
                        max = Math.max(max, g.parallelism);
                    }
                }
                return max;
            }
        }
    }

    /**
     * 重新计算并把结果写入控制器缓存（结构成型/更新事件入口）。
     */
    public static void recomputeForController(TileMultiblockMachineController controller) {
        if (!(controller instanceof IMigratedParallelismHolder)) {
            return;
        }
        DynamicMachine machine = controller.getFoundMachine();
        if (machine == null || !hasGrants(machine)) {
            return;
        }
        ((IMigratedParallelismHolder) controller).mmceaddition$setMigratedParallelism(computeForController(controller));
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static void migrateMachine(DynamicMachine machine) {
        String name = machine.getRegistryName().toString();
        if (isBlacklisted(name, machine.getRegistryName().getPath())) {
            return;
        }

        List<Grant> grants = new ArrayList<>();
        Set<AbstractModifierReplacement> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        // 单方块元件（截图中 JSON 的 "modifiers" 块即此类）
        for (List<SingleBlockModifierReplacement> list : machine.getModifiers().values()) {
            for (SingleBlockModifierReplacement rep : list) {
                if (seen.add(rep)) {
                    collectGrant(machine, rep, grants);
                }
            }
        }
        // 多方块元件（子结构匹配成功后成组生效的 modifier）
        for (MultiBlockModifierReplacement rep : machine.getMultiBlockModifiers()) {
            if (seen.add(rep)) {
                collectGrant(machine, rep, grants);
            }
        }

        // 即使为空也放入，用于区分“已迁移扫描”与“未迁移”
        GRANTS.put(name, Collections.unmodifiableList(grants));

        if (grants.isEmpty()) {
            return;
        }

        machine.setParallelizable(true);
        machine.setMaxParallelism(capFor(grants));
        ensureRecipesParallelized(machine);

        StringBuilder sb = new StringBuilder();
        for (Grant g : grants) {
            sb.append(' ').append(g.modifierName).append("=x").append(g.parallelism).append(',');
        }
        sb.setLength(sb.length() - 1);
        MMCEAddition.LOGGER.info("伪并行迁移: {} ->{}（策略 {}，上限 {}）",
                name, sb, strategy(), machine.getMaxParallelism());
    }

    private static void collectGrant(DynamicMachine machine, AbstractModifierReplacement rep, List<Grant> grants) {
        int n = convertReplacement(machine, rep);
        if (n > 1) {
            grants.add(new Grant(rep.getModifierName(), n));
        }
    }

    /**
     * 检查并转换单个 replacement 的 modifier 列表。
     *
     * @return 提取出的并行倍率；未检测到伪并行组时返回 -1
     */
    private static int convertReplacement(DynamicMachine machine, AbstractModifierReplacement rep) {
        List<RecipeModifier> mods = rep.getModifiers();
        if (mods == null || mods.size() < 2) {
            return -1;
        }

        // 按 target（物品/流体等需求类型）分组收集乘法 modifier
        Map<RequirementType<?, ?>, List<RecipeModifier>> mulInputs = new IdentityHashMap<>();
        Map<RequirementType<?, ?>, List<RecipeModifier>> mulOutputs = new IdentityHashMap<>();
        for (RecipeModifier m : mods) {
            if (m == null
                    || m.getOperation() != RecipeModifier.OPERATION_MULTIPLY
                    || m.affectsChance()
                    || m.getModifier() <= 1.0f
                    || m.getModifier() > MAX_REASONABLE_PARALLEL) {
                continue;
            }
            Map<RequirementType<?, ?>, List<RecipeModifier>> bucket =
                    m.getIOTarget() == IOType.INPUT ? mulInputs
                            : m.getIOTarget() == IOType.OUTPUT ? mulOutputs : null;
            if (bucket != null) {
                bucket.computeIfAbsent(m.getTarget(), k -> new ArrayList<>()).add(m);
            }
        }

        // 配对：input 与 output 倍率相等 => 伪并行组。
        // 第一阶段限同一 target（物品×N ↔ 物品×N）；第二阶段允许物品/流体跨类型配对
        // （物品输入×N ↔ 流体输出×N 也是典型的伪并行写法，只配同类型会漏掉一半组，
        //  残留的 ×N modifier 会导致"消耗被放大但产出不变"的严重失衡）。
        Set<RecipeModifier> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Integer> pairValues = new ArrayList<>();
        RecipeModifier firstPaired = null;
        for (Map.Entry<RequirementType<?, ?>, List<RecipeModifier>> entry : mulInputs.entrySet()) {
            List<RecipeModifier> outs = mulOutputs.get(entry.getKey());
            if (outs == null) {
                continue;
            }
            for (RecipeModifier in : entry.getValue()) {
                RecipeModifier match = null;
                for (RecipeModifier out : outs) {
                    if (!consumed.contains(out) && Float.compare(out.getModifier(), in.getModifier()) == 0) {
                        match = out;
                        break;
                    }
                }
                if (match != null) {
                    consumed.add(in);
                    consumed.add(match);
                    pairValues.add(Math.round(in.getModifier()));
                    if (firstPaired == null) {
                        firstPaired = in;
                    }
                }
            }
        }
        // 第二阶段：跨类型（仅物品/流体 target 之间）
        for (Map.Entry<RequirementType<?, ?>, List<RecipeModifier>> entry : mulInputs.entrySet()) {
            if (!isFakeParallelTarget(entry.getKey())) {
                continue;
            }
            for (RecipeModifier in : entry.getValue()) {
                if (consumed.contains(in)) {
                    continue;
                }
                RecipeModifier match = null;
                for (Map.Entry<RequirementType<?, ?>, List<RecipeModifier>> outEntry : mulOutputs.entrySet()) {
                    if (outEntry.getKey() == entry.getKey() || !isFakeParallelTarget(outEntry.getKey())) {
                        continue;
                    }
                    for (RecipeModifier out : outEntry.getValue()) {
                        if (!consumed.contains(out) && Float.compare(out.getModifier(), in.getModifier()) == 0) {
                            match = out;
                            break;
                        }
                    }
                    if (match != null) {
                        break;
                    }
                }
                if (match != null) {
                    consumed.add(in);
                    consumed.add(match);
                    pairValues.add(Math.round(in.getModifier()));
                    if (firstPaired == null) {
                        firstPaired = in;
                    }
                }
            }
        }

        if (pairValues.isEmpty()) {
            return -1;
        }

        int n = pairValues.get(0);
        for (int v : pairValues) {
            n = Math.max(n, v);
        }
        for (int v : pairValues) {
            if (v != n) {
                MMCEAddition.LOGGER.warn("伪并行迁移: 机器 {} 元件 {} 内存在不同倍率的成对 modifier（{} 与 {}），取最大值 {}",
                        machine.getRegistryName(), rep.getModifierName(), v, n, n);
                break;
            }
        }

        // 伪并行组已确认：同倍率的物品/流体输入/输出乘法 modifier 全部视为组成员摘除，
        // 即使它没能配成对（例如输入有物品+流体两种、输出只有物品）。
        // 残留的 ×N 输入 modifier 会在原生并行之上再放大一次消耗，必须清理干净。
        for (RecipeModifier m : mods) {
            if (consumed.contains(m) || m == null
                    || m.getOperation() != RecipeModifier.OPERATION_MULTIPLY
                    || m.affectsChance()
                    || !isFakeParallelTarget(m.getTarget())
                    || (m.getIOTarget() != IOType.INPUT && m.getIOTarget() != IOType.OUTPUT)) {
                continue;
            }
            if (Math.round(m.getModifier()) == n) {
                consumed.add(m);
            }
        }

        // 摘除伪并行组；若列表被清空则补一个 ×1 空操作 modifier，
        // 保证该元件匹配后仍会注册进控制器的 foundModifiers（运行时探测依据）
        List<RecipeModifier> remaining = new ArrayList<>(mods.size() - consumed.size() + 1);
        for (RecipeModifier m : mods) {
            if (!consumed.contains(m)) {
                remaining.add(m);
            }
        }
        if (remaining.isEmpty()) {
            remaining.add(new RecipeModifier(
                    firstPaired.getTarget(), IOType.INPUT, 1.0f, RecipeModifier.OPERATION_MULTIPLY, false));
        }
        setModifierList(rep, remaining);

        if (MMCEAdditionConfig.debugFakeParallelMigration) {
            MMCEAddition.LOGGER.info("伪并行迁移: {} / {} 摘除 {} 对 modifier，并行度 x{}",
                    machine.getRegistryName(), rep.getModifierName(), pairValues.size(), n);
        }
        return n;
    }

    /**
     * 机器级并行度上限：全部授权按策略聚合（钳制到合理范围）。
     */
    private static int capFor(List<Grant> grants) {
        switch (strategy()) {
            case SUM: {
                long sum = 0;
                for (Grant g : grants) {
                    sum += g.parallelism;
                }
                return (int) Math.min(Math.max(2, sum), MAX_REASONABLE_PARALLEL);
            }
            case PRODUCT: {
                long product = 1;
                for (Grant g : grants) {
                    product *= g.parallelism;
                }
                return (int) Math.min(Math.max(2, product), MAX_REASONABLE_PARALLEL);
            }
            case MAX:
            default: {
                int max = 2;
                for (Grant g : grants) {
                    max = Math.max(max, g.parallelism);
                }
                return max;
            }
        }
    }

    /**
     * 强制打开该机器所有配方的并行开关（幂等，可反复调用直到配方加载完成）。
     * <p>
     * 配方晚于机器注册加载，迁移时调用通常还拿不到配方；控制器查询并行度时
     * （{@code ParallelismControllerMixin}）会再次懒调用本方法兜底。
     */
    public static void ensureRecipesParallelized(DynamicMachine machine) {
        if (machine == null || !hasGrants(machine)) {
            return;
        }
        synchronized (FORCED_RECIPES) {
            if (FORCED_RECIPES.contains(machine)) {
                return;
            }
            // 只有真正标记到配方才算完成；配方尚未加载时留待下次重试
            if (forceRecipesParallelized(machine) > 0) {
                FORCED_RECIPES.add(machine);
            }
        }
    }

    /**
     * 把该机器的所有配方标记为可并行（MMCE 的配方级开关，无公开 setter，走反射）。
     *
     * @return 实际标记的配方数量
     */
    private static int forceRecipesParallelized(DynamicMachine machine) {
        int flagged = 0;
        try {
            if (recipeParallelizedField == null) {
                recipeParallelizedField = MachineRecipe.class.getDeclaredField("parallelized");
                recipeParallelizedField.setAccessible(true);
            }
            for (MachineRecipe recipe : machine.getAvailableRecipes()) {
                recipeParallelizedField.setBoolean(recipe, true);
                flagged++;
            }
        } catch (Exception e) {
            MMCEAddition.LOGGER.warn("伪并行迁移: 无法设置机器 {} 的配方并行标志，若并行未生效请检查 MMCE 配置 recipeParallelizeEnabledByDefault",
                    machine.getRegistryName(), e);
        }
        return flagged;
    }

    private static void setModifierList(AbstractModifierReplacement rep, List<RecipeModifier> newList) {
        try {
            if (modifierListField == null) {
                modifierListField = AbstractModifierReplacement.class.getDeclaredField("modifier");
                modifierListField.setAccessible(true);
            }
            modifierListField.set(rep, newList);
        } catch (Exception e) {
            throw new IllegalStateException("无法替换 modifier 列表: " + rep.getModifierName(), e);
        }
    }

    /** 伪并行组只允许物品/流体类 target 参与（能量、耗时等 modifier 绝不配对）。 */
    private static boolean isFakeParallelTarget(RequirementType<?, ?> target) {
        return target == hellfirepvp.modularmachinery.common.lib.RequirementTypesMM.REQUIREMENT_ITEM
                || target == hellfirepvp.modularmachinery.common.lib.RequirementTypesMM.REQUIREMENT_FLUID;
    }

    private static Strategy strategy() {
        switch (MMCEAdditionConfig.fakeParallelStrategy.trim().toLowerCase()) {
            case "sum":
                return Strategy.SUM;
            case "product":
                return Strategy.PRODUCT;
            default:
                return Strategy.MAX;
        }
    }

    private static boolean isBlacklisted(String fullName, String path) {
        for (String entry : MMCEAdditionConfig.fakeParallelMachineBlacklist) {
            if (entry.equals(fullName) || entry.equals(path)) {
                return true;
            }
        }
        return false;
    }
}

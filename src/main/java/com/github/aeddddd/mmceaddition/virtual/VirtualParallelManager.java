package com.github.aeddddd.mmceaddition.virtual;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import hellfirepvp.modularmachinery.common.crafting.MachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 虚拟并行管理器。
 * <p>
 * 与伪并行迁移（FakeParallelMigrator）并行存在的第二条并行轴：
 * 最终并行度 = 其他并行 × (1 + 虚拟并行仓并行)，
 * 其中虚拟并行仓并行为仓内匹配机器的机器数据数量之和，
 * 再经全局上限（int）钳制。无仓室时加数为 0，不放大。
 * <p>
 * 机器注册时（由 {@code MachineRegistryMixin} 触发）为所有非黑名单机器
 * 打开原生并行开关并抬高机器级并行上限（MMCE 内部钳制的兜底），
 * 配方级并行开关在首次查询时懒强制。
 */
public final class VirtualParallelManager {

    /**
     * 抬高机器级上限时的封顶值。
     * 这只是 MMCE 内部钳制的兜底值（最终值由 getMaxParallelism 覆写按配置上限钳制），
     * 避免把 Integer.MAX_VALUE 写进机器对象后在 MMCE 内部运算中溢出。
     */
    private static final int MACHINE_CAP_HEDGE = 65536;

    /** 已成功强制打开配方并行开关的机器对象（按身份；配方晚于机器加载，需懒重试）。 */
    private static final Set<DynamicMachine> FORCED_RECIPES = Collections.newSetFromMap(new IdentityHashMap<>());

    /** MachineRecipe#parallelized 的反射句柄（MMCE 未提供 setter）。 */
    private static Field recipeParallelizedField;

    private VirtualParallelManager() {
    }

    /**
     * 机器注册/重载入口：打开并行开关并抬高机器级上限（兜底 MMCE 内部可能的二次钳制）。
     */
    public static void onMachinesRegistered(Collection<DynamicMachine> machines) {
        if (!MMCEAdditionConfig.enableVirtualParallel || machines == null) {
            return;
        }
        int hedge = Math.min(Math.max(1, MMCEAdditionConfig.virtualParallelCap), MACHINE_CAP_HEDGE);
        for (DynamicMachine machine : machines) {
            if (machine == null || MachineMaterialAnalyzer.isBlacklisted(machine)) {
                continue;
            }
            try {
                machine.setParallelizable(true);
                if (machine.getMaxParallelism() < hedge) {
                    machine.setMaxParallelism(hedge);
                }
                ensureRecipesParallelized(machine);
            } catch (Exception e) {
                MMCEAddition.LOGGER.error("虚拟并行: 机器 {} 初始化失败", machine.getRegistryName(), e);
            }
        }
    }

    /**
     * 计算控制器当前的虚拟并行加数（公式中的 N：最终并行 = 其他并行 × (1 + N)）。
     * <p>
     * 遍历控制器的 foundComponents，找到全部虚拟并行仓，
     * 把其中"机器数据与当前机器匹配"的 count 求和（long 累加防溢出），
     * 钳制到配置的全局上限。无仓室或无匹配数据时返回 0（不放大）。
     * <p>
     * 每次调用实时计算，仓室存取/结构变化天然即时生效，无需缓存失效通道。
     * 可在异步配方搜索线程调用：只读地图与物品 NBT，异常时保守返回 0。
     */
    public static int computeVirtualFactor(TileMultiblockMachineController controller) {
        if (!MMCEAdditionConfig.enableVirtualParallel) {
            return 0;
        }
        DynamicMachine machine = controller.getFoundMachine();
        if (machine == null || MachineMaterialAnalyzer.isBlacklisted(machine)) {
            return 0;
        }
        String machineName = machine.getRegistryName().toString();
        int cap = Math.max(1, MMCEAdditionConfig.virtualParallelCap);
        try {
            long sum = 0;
            Map<TileEntity, ProcessingComponent<?>> components = controller.getFoundComponents();
            if (components == null) {
                return 0;
            }
            for (TileEntity tile : components.keySet()) {
                if (!(tile instanceof TileVirtualParallelHatch)) {
                    continue;
                }
                ItemStack data = ((TileVirtualParallelHatch) tile).getDataStack();
                if (data.isEmpty()) {
                    continue;
                }
                if (machineName.equals(ItemMachineData.getMachineName(data))) {
                    sum += ItemMachineData.getCount(data);
                    if (sum >= cap) {
                        return cap;
                    }
                }
            }
            return (int) Math.min(sum, cap);
        } catch (Exception e) {
            // 异步线程与主线程并发读写集合的极端情况：保守不放大
            return 0;
        }
    }

    /**
     * 强制打开该机器所有配方的并行开关（幂等，可反复调用直到配方加载完成）。
     */
    public static void ensureRecipesParallelized(DynamicMachine machine) {
        if (machine == null) {
            return;
        }
        synchronized (FORCED_RECIPES) {
            if (FORCED_RECIPES.contains(machine)) {
                return;
            }
            if (forceRecipesParallelized(machine) > 0) {
                FORCED_RECIPES.add(machine);
            }
        }
    }

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
            MMCEAddition.LOGGER.warn("虚拟并行: 无法设置机器 {} 的配方并行标志", machine.getRegistryName(), e);
        }
        return flagged;
    }
}

package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import hellfirepvp.modularmachinery.common.crafting.ActiveMachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.modifier.AbstractModifierReplacement;
import hellfirepvp.modularmachinery.common.modifier.RecipeModifier;
import hellfirepvp.modularmachinery.common.modifier.SingleBlockModifierReplacement;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mixin：把虚拟并行仓中机器数据记录的非并行类升级 modifier 真实注入配方上下文。
 * <p>
 * 机器数据在装配时按"替换式"记录升级（modifier 名 → 数量）。
 * 并行类升级已由迁移授权折算进 x(1+N) 的 N；产出类升级（物品/流体输出乘法）
 * 不注入——与并行放大相乘会指数膨胀；其余升级（速度/能耗等）每种只应用一次，
 * 记录数量可经合并无限累积，按数量连乘同样会指数膨胀。
 * <p>
 * 注入点选择 {@code createContext} 的 RETURN：此时上下文已由 MMCE 完成
 * 结构 modifier 的常规应用，我们只需追加数据记录的部分。
 */
@Mixin(value = DynamicMachine.class, remap = false)
public abstract class DynamicMachineMixin {

    /** 机器对象身份 → modifier 名到 modifier 列表的索引（/mm reload 后机器对象重建，缓存自然失效）。 */
    private static final Map<DynamicMachine, Map<String, List<RecipeModifier>>> MODIFIER_INDEX = new WeakHashMap<>();

    @Inject(method = "createContext", at = @At("RETURN"), remap = false)
    private void mmceaddition$injectRecordedUpgrades(ActiveMachineRecipe recipe, TileMultiblockMachineController controller,
                                                     CallbackInfoReturnable<RecipeCraftingContext> cir) {
        if (!MMCEAdditionConfig.enableVirtualParallel) {
            return;
        }
        DynamicMachine machine = (DynamicMachine) (Object) this;
        RecipeCraftingContext context = cir.getReturnValue();
        if (context == null) {
            return;
        }
        try {
            // 版本适配：2.2.2 的 getFoundComponents 是 Map<TileEntity, ProcessingComponent>，
            // 2.3.x 变为按组分桶的 Map<Long, Map<TileEntity, ProcessingComponent>>。
            // 统一按通配迭代，遇到嵌套 Map 就下钻一层。
            Map<?, ?> components = controller.getFoundComponents();
            if (components == null) {
                return;
            }
            String machineName = machine.getRegistryName().toString();
            for (Map.Entry<?, ?> entry : components.entrySet()) {
                if (entry.getKey() instanceof TileEntity) {
                    mmceaddition$injectForTile(machine, machineName, context, (TileEntity) entry.getKey());
                } else if (entry.getValue() instanceof Map) {
                    for (Object tile : ((Map<?, ?>) entry.getValue()).keySet()) {
                        if (tile instanceof TileEntity) {
                            mmceaddition$injectForTile(machine, machineName, context, (TileEntity) tile);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 异步配方搜索线程与主线程并发读写的极端情况：放弃本次注入，不影响主流程
        }
    }

    private void mmceaddition$injectForTile(DynamicMachine machine, String machineName,
                                            RecipeCraftingContext context, TileEntity tile) {
        if (!(tile instanceof TileVirtualParallelHatch)) {
            return;
        }
        ItemStack data = ((TileVirtualParallelHatch) tile).getDataStack();
        if (data.isEmpty() || !machineName.equals(ItemMachineData.getMachineName(data))) {
            return;
        }
        Map<String, Integer> upgrades = ItemMachineData.getUpgrades(data);
        if (upgrades.isEmpty()) {
            return;
        }
        Map<String, List<RecipeModifier>> index = modifierIndex(machine);
        for (Map.Entry<String, Integer> entry : upgrades.entrySet()) {
            List<RecipeModifier> modifiers = index.get(entry.getKey());
            if (modifiers == null || modifiers.isEmpty()) {
                continue;
            }
            // 每种升级只应用一次：数量已用于并行类折算；产出类 modifier 建索引时已剔除
            context.addModifier(modifiers);
        }
    }

    /**
     * 建立 modifier 名 → modifier 列表的索引（含单方块与多方块元件）。
     * 产出类 modifier（物品/流体输出乘法、放大数量）在建索引时剔除，
     * 这类效果与并行放大相乘会指数膨胀，虚拟升级中不生效。
     */
    private static Map<String, List<RecipeModifier>> modifierIndex(DynamicMachine machine) {
        synchronized (MODIFIER_INDEX) {
            Map<String, List<RecipeModifier>> cached = MODIFIER_INDEX.get(machine);
            if (cached != null) {
                return cached;
            }
            Map<String, List<RecipeModifier>> index = new LinkedHashMap<>();
            for (List<SingleBlockModifierReplacement> list : machine.getModifiers().values()) {
                for (SingleBlockModifierReplacement rep : list) {
                    if (rep != null && rep.getModifiers() != null) {
                        index.putIfAbsent(rep.getModifierName(), stripOutputMultipliers(rep.getModifiers()));
                    }
                }
            }
            for (AbstractModifierReplacement rep : machine.getMultiBlockModifiers()) {
                if (rep != null && rep.getModifiers() != null) {
                    index.putIfAbsent(rep.getModifierName(), stripOutputMultipliers(rep.getModifiers()));
                }
            }
            MODIFIER_INDEX.put(machine, Collections.unmodifiableMap(index));
            return index;
        }
    }

    /** 剔除产出类 modifier：OUTPUT 方向的非概率乘法放大。 */
    private static List<RecipeModifier> stripOutputMultipliers(List<RecipeModifier> modifiers) {
        List<RecipeModifier> filtered = null;
        for (int i = 0; i < modifiers.size(); i++) {
            RecipeModifier m = modifiers.get(i);
            boolean outputMultiplier = m != null
                    && m.getIOTarget() == IOType.OUTPUT
                    && m.getOperation() == RecipeModifier.OPERATION_MULTIPLY
                    && !m.affectsChance()
                    && m.getModifier() > 1.0f;
            if (outputMultiplier && filtered == null) {
                filtered = new java.util.ArrayList<>(modifiers.subList(0, i));
            } else if (!outputMultiplier && filtered != null) {
                filtered.add(m);
            }
        }
        return filtered != null ? Collections.unmodifiableList(filtered) : modifiers;
    }
}

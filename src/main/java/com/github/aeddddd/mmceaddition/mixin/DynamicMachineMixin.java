package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import hellfirepvp.modularmachinery.common.crafting.ActiveMachineRecipe;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
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
 * 并行类升级已由迁移授权折算进 x(1+N) 的 N；其余升级（速度/能耗等）
 * 在这里按数量逐次应用到上下文——与真实结构中每安装一个元件应用一次的语义一致。
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
            // 每个升级应用一次，与真实结构逐个安装元件的语义一致
            for (int i = 0; i < entry.getValue(); i++) {
                context.addModifier(modifiers);
            }
        }
    }

    /**
     * 建立 modifier 名 → modifier 列表的索引（含单方块与多方块元件）。
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
                        index.putIfAbsent(rep.getModifierName(), rep.getModifiers());
                    }
                }
            }
            for (AbstractModifierReplacement rep : machine.getMultiBlockModifiers()) {
                if (rep != null && rep.getModifiers() != null) {
                    index.putIfAbsent(rep.getModifierName(), rep.getModifiers());
                }
            }
            MODIFIER_INDEX.put(machine, Collections.unmodifiableMap(index));
            return index;
        }
    }
}

package com.github.aeddddd.mmceaddition.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;

/**
 * Mixin：让 ME 样板总成的内部样板显示在 AE 接口终端（Interface Terminal）中。
 * <p>
 * AE2UEL 的接口终端原生只列出 TileInterface / PartInterface；
 * MMCE 通过自己的 Mixin（{@code MixinContainerInterfaceTerminal}）把 MEPatternProvider 塞进去。
 * 这里用同样的方式注册样板总成：
 * <ul>
 *   <li>{@code regenList} TAIL：为每个在线样板总成创建 InvTracker 放入 diList，
 *       样板库存直接展示我们自己的 36 槽 patterns；</li>
 *   <li>{@code detectAndSendChanges} 中 MMCE 统计点之后：把样板总成的数量与缺失状态
 *       并入 MMCE 的统计字段（randomComplement$total/missing，由 MMCE 的 mixin 合入目标类），
 *       保证终端的"列表有变化"判定与全量同步逻辑对我们同样生效。</li>
 * </ul>
 * 注入点选在 MMCE 的统计注入点（isActive ordinal 0）之后、size() 重定向之前，
 * 与 MMCE 的注入/重定向不冲突。
 */
@Mixin(value = appeng.container.implementations.ContainerInterfaceTerminal.class, remap = false)
public class ContainerInterfaceTerminalMixin {

    @Shadow(remap = false)
    private IGrid grid;

    @Shadow(remap = false)
    @Final
    private Map<IInterfaceHost, Object> diList;

    private static Constructor<?> mmceaddition$invTrackerCtor;
    private static Field mmceaddition$invTrackerNameField;
    private static Field mmceaddition$mmceTotalField;
    private static Field mmceaddition$mmceMissingField;
    private static boolean mmceaddition$initFailed = false;

    private static void mmceaddition$ensureInit() throws Exception {
        if (mmceaddition$invTrackerCtor != null || mmceaddition$initFailed) {
            return;
        }
        try {
            Class<?> tracker = Class.forName("appeng.container.implementations.ContainerInterfaceTerminal$InvTracker");
            mmceaddition$invTrackerCtor = tracker.getDeclaredConstructor(
                    DualityInterface.class, net.minecraftforge.items.IItemHandler.class, String.class);
            mmceaddition$invTrackerCtor.setAccessible(true);
            mmceaddition$invTrackerNameField = tracker.getDeclaredField("unlocalizedName");
            mmceaddition$invTrackerNameField.setAccessible(true);
            Class<?> container = appeng.container.implementations.ContainerInterfaceTerminal.class;
            mmceaddition$mmceTotalField = container.getDeclaredField("randomComplement$total");
            mmceaddition$mmceTotalField.setAccessible(true);
            mmceaddition$mmceMissingField = container.getDeclaredField("randomComplement$missing");
            mmceaddition$mmceMissingField.setAccessible(true);
        } catch (Throwable t) {
            mmceaddition$initFailed = true;
            // 不再静默：反射依赖 MMCE 的 ae2 mixin 合入的字段，失败必须可见
            com.github.aeddddd.mmceaddition.MMCEAddition.LOGGER.warn(
                    "ME 样板总成的接口终端集成初始化失败（MMCE 的 ae2 mixin 可能未生效），终端将无法显示总成内样板", t);
            throw t instanceof Exception ? (Exception) t : new Exception(t);
        }
    }

    @Inject(method = "regenList", at = @At("TAIL"), remap = false)
    private void mmceaddition$addPatternAssemblies(NBTTagCompound data, CallbackInfo ci) {
        if (this.grid == null || mmceaddition$initFailed) {
            return;
        }
        try {
            mmceaddition$ensureInit();
            int added = 0;
            for (IGridNode gn : this.grid.getMachines(TileMEPatternAssembly.class)) {
                if (!gn.isActive()) {
                    continue;
                }
                TileMEPatternAssembly assembly = (TileMEPatternAssembly) gn.getMachine();
                DualityInterface duality = assembly.getInterfaceDuality();
                Object tracker = mmceaddition$invTrackerCtor.newInstance(
                        duality, assembly.getPatterns(), duality.getTermName());
                this.diList.put(assembly, tracker);
                added++;
            }
            if (added > 0) {
                com.github.aeddddd.mmceaddition.MMCEAddition.LOGGER.debug(
                        "接口终端：已注册 {} 个 ME 样板总成的样板列表", added);
            }
        } catch (Throwable t) {
            com.github.aeddddd.mmceaddition.MMCEAddition.LOGGER.warn("接口终端注册 ME 样板总成失败", t);
        }
    }

    @Inject(method = "detectAndSendChanges", remap = false,
            at = @At(value = "INVOKE", ordinal = 0, remap = false,
                    target = "Lappeng/api/networking/IGrid;getMachines(Ljava/lang/Class;)Lappeng/api/networking/IMachineSet;"))
    private void mmceaddition$countPatternAssemblies(CallbackInfo ci) {
        if (this.grid == null || mmceaddition$initFailed) {
            return;
        }
        try {
            mmceaddition$ensureInit();
            int total = 0;
            boolean missing = false;
            for (IGridNode gn : this.grid.getMachines(TileMEPatternAssembly.class)) {
                if (!gn.isActive()) {
                    continue;
                }
                total++;
                Object tracker = this.diList.get(gn.getMachine());
                if (tracker == null) {
                    missing = true;
                    continue;
                }
                String shown = (String) mmceaddition$invTrackerNameField.get(tracker);
                String actual = ((TileMEPatternAssembly) gn.getMachine()).getInterfaceDuality().getTermName();
                if (!Objects.equals(shown, actual)) {
                    missing = true;
                }
            }
            if (total > 0) {
                mmceaddition$mmceTotalField.setInt(this, mmceaddition$mmceTotalField.getInt(this) + total);
                if (missing) {
                    mmceaddition$mmceMissingField.setBoolean(this, true);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}

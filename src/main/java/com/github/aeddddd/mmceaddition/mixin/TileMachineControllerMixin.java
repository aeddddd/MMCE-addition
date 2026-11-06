package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.manager.HatchProfiler;
import hellfirepvp.modularmachinery.common.tiles.TileMachineController;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：机器控制器 tick 耗时采样。
 * <p>
 * 控制器是 MMCE 机器 tick 开销的主要来源（配方检查、组件轮询等），
 * 在 {@code doControllerTick} 头尾插桩，把耗时记入 {@link HatchProfiler}，
 * 供仓室管理工具的性能分析页按"哪台机器最贵"排序。
 * 耗时按 {@link HatchProfiler#POINT_MACHINE_CONTROLLER} 独立计时。
 * <p>
 * 注意：本 Mixin 只覆盖 {@code TileMachineController.doControllerTick}；
 * MMCE 的工厂控制器（{@code TileFactoryController}）自行覆写了该方法，
 * 不在插桩范围内，其耗时列为 0（未采样）。
 * 采样关闭时只有一个 boolean 判断，开销可忽略。
 */
@Mixin(value = TileMachineController.class, remap = false)
public abstract class TileMachineControllerMixin {

    @Inject(method = "doControllerTick", at = @At("HEAD"), remap = false)
    private void mmceaddition$profileHead(CallbackInfo ci) {
        HatchProfiler.INSTANCE.begin(HatchProfiler.POINT_MACHINE_CONTROLLER);
    }

    @Inject(method = "doControllerTick", at = @At("RETURN"), remap = false)
    private void mmceaddition$profileReturn(CallbackInfo ci) {
        long elapsedNanos = HatchProfiler.INSTANCE.end(HatchProfiler.POINT_MACHINE_CONTROLLER);
        if (elapsedNanos >= 0) {
            HatchProfiler.INSTANCE.record((TileEntity) (Object) this, elapsedNanos);
        }
    }
}

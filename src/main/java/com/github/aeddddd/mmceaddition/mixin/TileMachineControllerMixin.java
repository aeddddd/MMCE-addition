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
 * 采样关闭时只有一个 boolean 判断，开销可忽略。
 */
@Mixin(value = TileMachineController.class, remap = false)
public abstract class TileMachineControllerMixin {

    @Inject(method = "doControllerTick", at = @At("HEAD"), remap = false)
    private void mmceaddition$profileHead(CallbackInfo ci) {
        if (HatchProfiler.INSTANCE.isEnabled()) {
            HatchProfiler.tickStart = System.nanoTime();
        }
    }

    @Inject(method = "doControllerTick", at = @At("RETURN"), remap = false)
    private void mmceaddition$profileReturn(CallbackInfo ci) {
        if (HatchProfiler.INSTANCE.isEnabled()) {
            HatchProfiler.INSTANCE.record((TileEntity) (Object) this,
                    System.nanoTime() - HatchProfiler.tickStart);
        }
    }
}

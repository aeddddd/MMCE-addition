package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.manager.HatchProfiler;
import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileEnergyOutputHatch;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：能源输出仓的自动 IO 门控 + tick 耗时采样。
 * <p>
 * MMCE 的能源输出仓在 {@code update}（生产环境为 SRG 名 {@code func_73660_a}）中
 * 向邻接方块推送能量（FE/RF/GT/IC2/FluxNetworks/DE 核心）；
 * 开关关闭时在方法头部直接取消，跳过本 tick 的自动推送。
 * 耗时按 {@link HatchProfiler#POINT_ENERGY_OUTPUT_HATCH} 独立计时，RETURN 处记入
 * {@link HatchProfiler}（采样关闭时只有一次 volatile 读）；
 * HEAD 被取消时显式放弃本次计时，等价于该 tick 零耗时。
 */
@Mixin(value = TileEnergyOutputHatch.class, remap = false)
public abstract class TileEnergyOutputHatchMixin {

    @Inject(method = {"func_73660_a", "update"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$gateAutoIO(CallbackInfo ci) {
        HatchProfiler.INSTANCE.begin(HatchProfiler.POINT_ENERGY_OUTPUT_HATCH);
        if (!((IAutoIOControllable) this).mmceaddition$isAutoIOEnabled()) {
            HatchProfiler.INSTANCE.abort(HatchProfiler.POINT_ENERGY_OUTPUT_HATCH);
            ci.cancel();
        }
    }

    @Inject(method = {"func_73660_a", "update"}, at = @At("RETURN"), remap = false)
    private void mmceaddition$profileTick(CallbackInfo ci) {
        long elapsedNanos = HatchProfiler.INSTANCE.end(HatchProfiler.POINT_ENERGY_OUTPUT_HATCH);
        if (elapsedNanos >= 0) {
            HatchProfiler.INSTANCE.record((TileEntity) (Object) this, elapsedNanos);
        }
    }
}

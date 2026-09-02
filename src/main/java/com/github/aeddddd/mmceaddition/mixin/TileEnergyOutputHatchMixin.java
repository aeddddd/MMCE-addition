package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileEnergyOutputHatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：能源输出仓的自动 IO 门控。
 * <p>
 * MMCE 的能源输出仓在 {@code update}（生产环境为 SRG 名 {@code func_73660_a}）中
 * 向邻接方块推送能量（FE/RF/GT/IC2/FluxNetworks/DE 核心）；
 * 开关关闭时在方法头部直接取消，跳过本 tick 的自动推送。
 */
@Mixin(value = TileEnergyOutputHatch.class, remap = false)
public abstract class TileEnergyOutputHatchMixin {

    @Inject(method = {"func_73660_a", "update"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$gateAutoIO(CallbackInfo ci) {
        if (!((IAutoIOControllable) this).mmceaddition$isAutoIOEnabled()) {
            ci.cancel();
        }
    }
}

package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileItemOutputBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：物品输出总线的自动 IO 门控。
 * <p>
 * MMCE 的物品输出总线在 {@code doRestrictedTick} 中向邻接容器推送物品；
 * 开关关闭时在方法头部直接取消，跳过本 tick 的自动推送。
 */
@Mixin(value = TileItemOutputBus.class, remap = false)
public abstract class TileItemOutputBusMixin {

    @Inject(method = "doRestrictedTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$gateAutoIO(CallbackInfo ci) {
        if (!((IAutoIOControllable) this).mmceaddition$isAutoIOEnabled()) {
            ci.cancel();
        }
    }
}

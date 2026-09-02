package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileItemInputBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：物品输入总线的自动 IO 门控。
 * <p>
 * MMCE 的物品输入总线在 {@code doRestrictedTick} 中从邻接容器抽取物品；
 * 开关关闭时在方法头部直接取消，跳过本 tick 的自动抽取。
 */
@Mixin(value = TileItemInputBus.class, remap = false)
public abstract class TileItemInputBusMixin {

    @Inject(method = "doRestrictedTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$gateAutoIO(CallbackInfo ci) {
        if (!((IAutoIOControllable) this).mmceaddition$isAutoIOEnabled()) {
            ci.cancel();
        }
    }
}

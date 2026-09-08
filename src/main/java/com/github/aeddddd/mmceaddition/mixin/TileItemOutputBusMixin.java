package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.manager.HatchProfiler;
import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileItemOutputBus;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：物品输出总线的自动 IO 门控 + tick 耗时采样。
 * <p>
 * MMCE 的物品输出总线在 {@code doRestrictedTick} 中向邻接容器推送物品；
 * 开关关闭时在方法头部直接取消，跳过本 tick 的自动推送。
 * RETURN 处的插桩把本 tick 耗时记入 {@link HatchProfiler}（采样关闭时零开销）。
 */
@Mixin(value = TileItemOutputBus.class, remap = false)
public abstract class TileItemOutputBusMixin {

    @Inject(method = "doRestrictedTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$gateAutoIO(CallbackInfo ci) {
        if (HatchProfiler.INSTANCE.isEnabled()) {
            HatchProfiler.tickStart = System.nanoTime();
        }
        if (!((IAutoIOControllable) this).mmceaddition$isAutoIOEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "doRestrictedTick", at = @At("RETURN"), remap = false)
    private void mmceaddition$profileTick(CallbackInfo ci) {
        if (HatchProfiler.INSTANCE.isEnabled()) {
            HatchProfiler.INSTANCE.record((TileEntity) (Object) this,
                    System.nanoTime() - HatchProfiler.tickStart);
        }
    }
}

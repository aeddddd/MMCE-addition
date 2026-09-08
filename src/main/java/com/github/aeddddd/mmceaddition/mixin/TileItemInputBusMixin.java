package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.manager.HatchProfiler;
import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.TileItemInputBus;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：物品输入总线的自动 IO 门控 + tick 耗时采样。
 * <p>
 * MMCE 的物品输入总线在 {@code doRestrictedTick} 中从邻接容器抽取物品；
 * 开关关闭时在方法头部直接取消，跳过本 tick 的自动抽取。
 * RETURN 处的插桩把本 tick 耗时记入 {@link HatchProfiler}（采样关闭时零开销）；
 * HEAD 被取消时不产生记录，等价于该 tick 零耗时。
 */
@Mixin(value = TileItemInputBus.class, remap = false)
public abstract class TileItemInputBusMixin {

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

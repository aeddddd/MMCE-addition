package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.FailureDetails;
import hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Mixin：配方完成检查（{@code canFinishCrafting}，例如输出仓已满）失败时，
 * 同样把完整错误列表缓存到控制器上。
 * <p>
 * {@code onFinished} 失败路径只把第一条错误写入 {@code CraftingStatus} 并进入
 * waitForFinish 重试，这里捕获完整列表；成功路径顺带清除搜索阶段残留的失败详情。
 */
@Mixin(value = hellfirepvp.modularmachinery.common.machine.RecipeThread.class, remap = false)
public abstract class RecipeThreadMixin {

    @Shadow(remap = false)
    protected TileMultiblockMachineController ctrl;

    @Inject(
            method = "onFinished()V",
            at = @At("RETURN"),
            locals = LocalCapture.CAPTURE_FAILSOFT,
            require = 0,
            remap = false)
    private void mmceaddition$captureFinishFailureDetails(CallbackInfo ci,
                                                          RecipeCraftingContext.CraftingCheckResult checkResult) {
        if (checkResult == null) {
            return;
        }
        if (checkResult.isFailure()) {
            FailureDetails.record(this.ctrl, checkResult);
        } else {
            FailureDetails.clear(this.ctrl);
        }
    }
}

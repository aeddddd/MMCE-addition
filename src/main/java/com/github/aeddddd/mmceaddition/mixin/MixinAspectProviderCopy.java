package com.github.aeddddd.mmceaddition.mixin;

import appeng.api.networking.security.IActionHost;
import com.github.aeddddd.mmceaddition.compat.NetworkResourceCompat;
import com.github.aeddddd.mmceaddition.tile.provider.INetworkAspectJar;
import kport.modularmagic.common.crafting.helper.AspectProviderCopy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.tiles.essentia.TileJarFillable;

/**
 * 让 AspectProviderCopy 快照识别网络源质代理 jar.
 *
 * <p>背景：RequirementAspect 的模拟检查（canStartCrafting/getMaxParallelism）在
 * AspectProviderCopy 快照上执行，且快照的 aspect 相等性检查（{@code tt == this.aspect}）
 * 对网络代理恒失败（代理 jar 的 aspect 字段为 null，可供应任意源质）。
 * 本 mixin 对网络代理绕过该检查，直接查询网络源质存量（模拟语义）。</p>
 *
 * <p>非模拟路径（RequirementAspect.takeAll 的 original 调用）由
 * NetworkAspectJar 自身的方法重写承担，不经此 mixin。</p>
 *
 * <p><b>加载门控</b>：本类签名含 TC 类型，由 MMCEAdditionMixinPlugin
 * 在 thaumcraft + thaumicenergistics 缺席时跳过应用。</p>
 */
@Mixin(value = AspectProviderCopy.class, remap = false)
public abstract class MixinAspectProviderCopy {

    @Shadow
    public abstract TileJarFillable getOriginal();

    /**
     * 网络代理：绕过 aspect 相等性检查，直接模拟查询网络存量（全有或全无）.
     */
    @Inject(method = "takeFromContainer", at = @At("HEAD"), cancellable = true)
    private void mmceaddition$networkTake(Aspect tt, int am, CallbackInfoReturnable<Boolean> cir) {
        TileJarFillable original = getOriginal();
        if (original instanceof INetworkAspectJar) {
            IActionHost host = ((INetworkAspectJar) original).getNetworkHost();
            cir.setReturnValue(
                    NetworkResourceCompat.extractEssentia(host, tt.getTag(), am, true) >= am);
        }
    }

    /**
     * 网络代理：OUTPUT 方向的容量模拟——网络视为无限容量，全部接收.
     */
    @Inject(method = "addToContainer", at = @At("HEAD"), cancellable = true)
    private void mmceaddition$networkAdd(Aspect tt, int am, CallbackInfoReturnable<Integer> cir) {
        if (getOriginal() instanceof INetworkAspectJar) {
            cir.setReturnValue(0);
        }
    }
}

package com.github.aeddddd.mmceaddition.tile.provider;

import ae2.api.networking.security.IActionHost;
import com.github.aeddddd.mmceaddition.compat.NetworkResourceCompat;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.tiles.essentia.TileJarFillable;

/**
 * 网络源质代理 jar（不放置进世界的虚拟 Tile）.
 *
 * <p>继承 TC6 原生 {@link TileJarFillable}，把消耗委托给 AE 网络的源质通道
 * （经 {@link NetworkResourceCompat} 反射调用 ThaumicEnergistics）。</p>
 *
 * <p><b>关键设计</b>：{@code RequirementAspect.takeAll} 非模拟路径直接读取公有字段
 * {@code original.amount} 计算可取量，该字段无法重写——因此本类将 {@link #amount}
 * 恒置为 {@link Integer.MAX_VALUE}，使上限核算完全落到可重写的
 * {@link #takeFromContainer(Aspect, int)}（内部做新鲜的网络存量检查，全有或全无）。</p>
 *
 * <p>模拟路径（AspectProviderCopy 快照）由 MixinAspectProviderCopy 接管：
 * 快照的 aspect 字段为 null 时原生的 aspect 相等性检查会失败，mixin 对
 * 网络代理 jar 绕过该检查并直接查询网络。</p>
 *
 * <p><b>条件加载</b>：类声明硬引用 TC6，仅在 thaumcraft + thaumicenergistics
 * 在场时由 AspectComponentFactory 实例化。</p>
 */
public class NetworkAspectJar extends TileJarFillable implements INetworkAspectJar {

    private final IActionHost host;

    public NetworkAspectJar(IActionHost host) {
        this.host = host;
        // takeAll 非模拟路径直接读公有字段 original.amount 作为可取上限
        this.amount = Integer.MAX_VALUE;
        // aspect 保持 null：代理可提供任意源质，模拟路径的 aspect 检查由 mixin 绕过
    }

    @Override
    public IActionHost getNetworkHost() {
        return host;
    }

    @Override
    public boolean takeFromContainer(Aspect tt, int am) {
        if (tt == null || am <= 0) {
            return false;
        }
        // 全有或全无：先模拟确认网络存量充足再实际扣除
        if (NetworkResourceCompat.extractEssentia(host, tt.getTag(), am, true) < am) {
            return false;
        }
        return NetworkResourceCompat.extractEssentia(host, tt.getTag(), am, false) >= am;
    }

    @Override
    public int addToContainer(Aspect tt, int am) {
        // INPUT 专用代理：OUTPUT 需求不在适配范围内；返回 0 = 全部接收（防御性实现）
        return 0;
    }
}

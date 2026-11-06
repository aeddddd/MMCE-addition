package com.github.aeddddd.mmceaddition.tile.provider;

import ae2.api.networking.security.IActionHost;
import com.github.aeddddd.mmceaddition.compat.NetworkResourceCompat;
import kport.modularmagic.common.tile.TileStarlightInput;

/**
 * 网络星能代理 provider（不放置进世界的虚拟 Tile）.
 *
 * <p>继承 MMCE 原生 {@link TileStarlightInput}（RequirementStarlight.isValidComponent
 * 硬 instanceof 该类），把 {@link #getStarlightStored()} 委托给 AE 网络星能存量
 * （经 {@link NetworkResourceCompat} 通过原生 AE2S 资源键访问网络 Starlight）。</p>
 *
 * <p><b>语义说明</b>：MMCE 原生的星能 INPUT 需求只检查存量、不消耗
 * （原生 TileStarlightInput 依赖自然衰减），本代理保持同一语义——
 * 仅做存量透视，不从网络扣除星能。</p>
 *
 * <p><b>条件加载</b>：类声明硬引用 Astral Sorcery 链路（TileReceiverBase），
 * 仅在 AS 在场时由 StarlightComponentFactory 实例化。</p>
 */
public class NetworkStarlightInput extends TileStarlightInput {

    private final IActionHost host;

    public NetworkStarlightInput(IActionHost host) {
        this.host = host;
    }

    @Override
    public int getStarlightStored() {
        return (int) Math.min(NetworkResourceCompat.getStoredStarlight(host), Integer.MAX_VALUE);
    }
}

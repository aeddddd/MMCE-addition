package com.github.aeddddd.mmceaddition.tile.provider;

import appeng.api.networking.security.IActionHost;
import hellfirepvp.modularmachinery.common.crafting.helper.ComponentSelectorTag;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import kport.modularmagic.common.tile.machinecomponent.MachineComponentStarlightProviderInput;

import javax.annotation.Nullable;

/**
 * 星能网络组件工厂.
 *
 * <p>硬引用 Astral Sorcery 链路（经 NetworkStarlightInput → TileReceiverBase），
 * 仅允许在 AS 在场时由 {@link AssemblyResourceComponents} 反射加载。</p>
 */
public final class StarlightComponentFactory {

    private StarlightComponentFactory() {
    }

    public static MachineComponent<?> createComponent(IActionHost host) {
        return new MachineComponentStarlightProviderInput(new NetworkStarlightInput(host), IOType.INPUT);
    }
}

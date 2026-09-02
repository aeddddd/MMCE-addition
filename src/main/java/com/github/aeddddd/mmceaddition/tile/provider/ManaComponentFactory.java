package com.github.aeddddd.mmceaddition.tile.provider;

import appeng.api.networking.security.IActionHost;
import hellfirepvp.modularmachinery.common.crafting.helper.ComponentSelectorTag;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import kport.modularmagic.common.tile.machinecomponent.MachineComponentManaProvider;

import javax.annotation.Nullable;

/**
 * 魔力网络组件工厂.
 *
 * <p>硬引用 Botania 链路（经 MachineComponentManaProvider → TileManaProvider），
 * 仅允许在 Botania 在场时由 {@link AssemblyResourceComponents} 反射加载。</p>
 */
public final class ManaComponentFactory {

    private ManaComponentFactory() {
    }

    public static MachineComponent<?> createComponent(IActionHost host) {
        return new MachineComponentManaProvider(IOType.INPUT, new NetworkManaProvider(host));
    }
}

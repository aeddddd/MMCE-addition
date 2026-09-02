package com.github.aeddddd.mmceaddition.tile.provider;

import appeng.api.networking.security.IActionHost;
import hellfirepvp.modularmachinery.common.crafting.helper.ComponentSelectorTag;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import kport.modularmagic.common.crafting.helper.AspectProviderCopy;
import kport.modularmagic.common.tile.machinecomponent.MachineComponentAspectProvider;

import javax.annotation.Nullable;

/**
 * 源质网络组件工厂.
 *
 * <p>硬引用 TC6 链路（经 NetworkAspectJar → TileJarFillable），
 * 仅允许在 thaumcraft + thaumicenergistics 在场时由
 * {@link AssemblyResourceComponents} 反射加载。</p>
 */
public final class AspectComponentFactory {

    private AspectComponentFactory() {
    }

    public static MachineComponent<?> createComponent(IActionHost host) {
        return new MachineComponentAspectProvider(new AspectProviderCopy(new NetworkAspectJar(host)), IOType.INPUT);
    }
}

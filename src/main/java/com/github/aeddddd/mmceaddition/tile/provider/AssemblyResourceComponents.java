package com.github.aeddddd.mmceaddition.tile.provider;

import ae2.api.networking.security.IActionHost;
import com.github.aeddddd.mmceaddition.compat.NetworkResourceCompat;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import net.minecraftforge.fml.common.Loader;

import javax.annotation.Nullable;

/**
 * 网络资源组件的门控工厂（不含任何 TC/Botania/AS 类型引用）.
 *
 * <p>承担两重隔离：
 * <ol>
 *   <li>总成方块类（无条件加载）的常量池中不出现条件加载类的符号——
 *       具体的 provider/组件构造全部下沉到各资源专属的 Factory 类，
 *       此处仅以 {@link Class#forName} + {@code Method.invoke} 触达；</li>
 *   <li>按 mod 在场状态（botania / astralsorcery / thaumcraft+thaumicenergistics）
 *       与对应网络 API 可用性决定是否提供组件。</li>
 * </ol>
 */
public final class AssemblyResourceComponents {

    private static final String MANA_FACTORY =
            "com.github.aeddddd.mmceaddition.tile.provider.ManaComponentFactory";
    private static final String STARLIGHT_FACTORY =
            "com.github.aeddddd.mmceaddition.tile.provider.StarlightComponentFactory";
    private static final String ASPECT_FACTORY =
            "com.github.aeddddd.mmceaddition.tile.provider.AspectComponentFactory";

    private AssemblyResourceComponents() {
    }

    /**
     * 创建网络魔力输入组件；Botania 或 AE2Enhanced Mana API 不可用时返回 null.
     */
    @Nullable
    public static MachineComponent<?> createManaInput(IActionHost host) {
        if (!Loader.isModLoaded("botania") || !NetworkResourceCompat.isManaAvailable()) {
            return null;
        }
        return invokeFactory(MANA_FACTORY, host);
    }

    /**
     * 创建网络星能输入组件；Astral Sorcery 或 AE2Enhanced Starlight API 不可用时返回 null.
     */
    @Nullable
    public static MachineComponent<?> createStarlightInput(IActionHost host) {
        if (!Loader.isModLoaded("astralsorcery") || !NetworkResourceCompat.isStarlightAvailable()) {
            return null;
        }
        return invokeFactory(STARLIGHT_FACTORY, host);
    }

    /**
     * 创建网络源质输入组件；Thaumcraft / ThaumicEnergistics 不可用时返回 null.
     */
    @Nullable
    public static MachineComponent<?> createAspectInput(IActionHost host) {
        if (!Loader.isModLoaded("thaumcraft") || !NetworkResourceCompat.isEssentiaAvailable()) {
            return null;
        }
        return invokeFactory(ASPECT_FACTORY, host);
    }

    @Nullable
    private static MachineComponent<?> invokeFactory(String className, IActionHost host) {
        try {
            Object result = Class.forName(className)
                    .getMethod("createComponent", IActionHost.class)
                    .invoke(null, host);
            return (MachineComponent<?>) result;
        } catch (Exception e) {
            return null;
        }
    }
}

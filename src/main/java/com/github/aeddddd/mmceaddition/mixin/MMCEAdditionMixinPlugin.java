package com.github.aeddddd.mmceaddition.mixin;

import net.minecraftforge.fml.common.Loader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * MMCE-addition mixin 条件加载插件.
 *
 * <p>当前仅用于门控 {@code MixinAspectProviderCopy}：其签名含 Thaumcraft 类型，
 * 且仅在源质网络适配有意义的环境（thaumcraft + thaumicenergistics 在场）应用。</p>
 */
public class MMCEAdditionMixinPlugin implements IMixinConfigPlugin {

    private static final String ASPECT_COPY_MIXIN =
            "com.github.aeddddd.mmceaddition.mixin.MixinAspectProviderCopy";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (ASPECT_COPY_MIXIN.equals(mixinClassName)) {
            return Loader.isModLoaded("thaumcraft") && Loader.isModLoaded("thaumicenergistics");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}

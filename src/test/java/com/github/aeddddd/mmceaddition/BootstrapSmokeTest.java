package com.github.aeddddd.mmceaddition;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 探针测试：确认在 ForgeGradle 2.3 的 test 任务里能否引导起 Minecraft 的注册表。
 * 若能，就可以为依赖 ItemStack 的纯逻辑（Long 缓冲、槽位记账）写真实单元测试。
 * <p>
 * 引导逻辑统一放在 {@link MinecraftBootstrap} 基类里，本类只做最基础的环境自检。
 */
class BootstrapSmokeTest extends MinecraftBootstrap {

    @Test
    void itemStackIsUsable() {
        // 守护：注册表已就绪，ItemStack 的基本构造与 isEmpty 语义可用
        // （这是所有 Long 缓冲测试的前置条件）。
        ItemStack stack = new ItemStack(Items.STICK);
        assertFalse(stack.isEmpty(), "Bootstrap.register() 后 ItemStack 不应为空");
        assertNotNull(Items.STICK.getRegistryName(), "物品必须有注册名，NBT 往返依赖它");
    }
}

package com.github.aeddddd.mmceaddition.proxy;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.command.CommandMMCEAddition;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/**
 * 公共代理类。
 * <p>
 * 代理模式（Sided Proxy）是 Forge 模组开发中用来分离客户端/服务端代码的核心机制。
 * CommonProxy 中的代码在物理客户端和物理服务端都会执行，因此不能引用任何只在客户端存在的类
 *（例如 net.minecraft.client.*、net.minecraftforge.client.* 等）。
 * <p>
 * 客户端专有逻辑应放在 {@link ClientProxy} 中，并在本类中通过空方法或默认实现占位。
 */
public class CommonProxy {

    /**
     * 预初始化。
     * <p>
     * 这里执行的是两端都需要的前期准备工作：
     * <ul>
     *   <li>同步 Forge 配置文件（把 .cfg 文件中的值读到内存）</li>
     *   <li>把 RegistryHandler 注册到 Forge 事件总线，等待方块/物品注册事件</li>
     *   <li>把 MEAsyncOutputManager 注册到事件总线，让它能接收 ServerTickEvent</li>
     * </ul>
     *
     * @param event 预初始化事件
     */
    public void preInit(FMLPreInitializationEvent event) {
        // ConfigManager.sync 会读取 config/mmceaddition.cfg（如果不存在则创建），
        // 并把 @Config 注解的字段与文件内容同步。
        ConfigManager.sync(MMCEAddition.MODID, net.minecraftforge.common.config.Config.Type.INSTANCE);

        // MinecraftForge.EVENT_BUS 是 Forge 的主事件总线。
        // 注册后，带有 @SubscribeEvent 注解的方法就能收到对应事件。
        MinecraftForge.EVENT_BUS.register(new RegistryHandler());
        MinecraftForge.EVENT_BUS.register(MEAsyncOutputManager.INSTANCE);
    }

    /**
     * 初始化。
     * <p>
     * 当前没有需要在 Init 阶段执行的通用逻辑，保留空方法供子类扩展。
     *
     * @param event 初始化事件
     */
    public void init(FMLInitializationEvent event) {
    }

    /**
     * 后初始化。
     * <p>
     * 当前没有需要在 PostInit 阶段执行的通用逻辑，保留空方法供子类扩展。
     *
     * @param event 后初始化事件
     */
    public void postInit(FMLPostInitializationEvent event) {
    }

    /**
     * 服务端启动。
     * <p>
     * 在物理服务端（包括单人游戏内嵌服务端）启动时调用，用于注册命令、初始化存档数据等。
     *
     * @param event 服务端启动事件
     */
    public void onServerStarting(FMLServerStartingEvent event) {
        // 注册自定义管理命令。
        // FMLServerStartingEvent 只在服务端可用，所以命令注册放在 CommonProxy 而不是 ClientProxy。
        event.registerServerCommand(new CommandMMCEAddition());
    }
}

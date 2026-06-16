package com.github.aeddddd.mmceaddition.proxy;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端代理类。
 * <p>
 * ClientProxy 继承自 CommonProxy，只在物理客户端加载。
 * 这里可以安全引用客户端专用类，例如：
 * <ul>
 *   <li>net.minecraft.client.*</li>
 *   <li>net.minecraftforge.client.*</li>
 *   <li>appeng.client.*</li>
 * </ul>
 * <p>
 * 本模组把模型注册、Tooltip 处理等逻辑放在 {@link com.github.aeddddd.mmceaddition.client.ClientEventHandler}
 * 中，并通过 {@code @Mod.EventBusSubscriber(value = Side.CLIENT)} 自动注册，
 * 因此 ClientProxy 目前只需调用父类的默认实现。
 */
public class ClientProxy extends CommonProxy {

    /**
     * 客户端预初始化。
     * <p>
     * 可以在这里注册：
     * <ul>
     *   <li>方块/物品模型（ModelRegistryEvent）</li>
     *   <li>纹理染色（BlockColors / ItemColors）</li>
     *   <li>自定义渲染器（TileEntitySpecialRenderer）</li>
     * </ul>
     *
     * @param event 预初始化事件
     */
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    /**
     * 客户端初始化。
     *
     * @param event 初始化事件
     */
    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    /**
     * 客户端后初始化。
     *
     * @param event 后初始化事件
     */
    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}

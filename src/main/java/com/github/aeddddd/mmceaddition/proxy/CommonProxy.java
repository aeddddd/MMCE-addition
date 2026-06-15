package com.github.aeddddd.mmceaddition.proxy;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * 公共代理。
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        ConfigManager.sync(MMCEAddition.MODID, net.minecraftforge.common.config.Config.Type.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new RegistryHandler());
        MinecraftForge.EVENT_BUS.register(MEAsyncOutputManager.INSTANCE);
    }

    public void init(FMLInitializationEvent event) {
    }

    public void postInit(FMLPostInitializationEvent event) {
    }
}

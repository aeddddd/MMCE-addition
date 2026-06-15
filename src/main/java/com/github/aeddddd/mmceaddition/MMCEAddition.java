package com.github.aeddddd.mmceaddition;

import com.github.aeddddd.mmceaddition.proxy.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

/**
 * MMCE Addition 主类。
 */
@Mod(
        modid = MMCEAddition.MODID,
        name = MMCEAddition.NAME,
        version = MMCEAddition.VERSION,
        dependencies = "required-after:modularmachinery;required-after:appliedenergistics2"
)
public class MMCEAddition implements ILateMixinLoader {

    public static final String MODID = "mmceaddition";
    public static final String NAME = "MMCE Addition";
    public static final String VERSION = "1.0-SNAPSHOT";

    public static final String CLIENT_PROXY = "com.github.aeddddd.mmceaddition.proxy.ClientProxy";
    public static final String COMMON_PROXY = "com.github.aeddddd.mmceaddition.proxy.CommonProxy";

    @Mod.Instance(MODID)
    public static MMCEAddition instance;

    @SidedProxy(clientSide = CLIENT_PROXY, serverSide = COMMON_PROXY)
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        proxy.onServerStarting(event);
    }

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.mmceaddition.json");
    }
}

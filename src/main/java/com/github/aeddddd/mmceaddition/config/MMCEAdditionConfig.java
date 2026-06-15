package com.github.aeddddd.mmceaddition.config;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * MMCE Addition 配置文件。
 */
@Config(modid = MMCEAddition.MODID)
@Config.LangKey("config.mmceaddition.title")
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID)
public class MMCEAdditionConfig {

    @Config.Name("enable_me_item_bus_compat")
    @Config.Comment("允许异步 ME 物品输出总线替换原 MMCE 各类物品输出总线的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_me_item_bus_compat")
    public static boolean enableMEItemBusCompat = true;

    @Config.Name("enable_me_fluid_bus_compat")
    @Config.Comment("允许异步 ME 流体输出仓替换原 MMCE 各类流体输出仓的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_me_fluid_bus_compat")
    public static boolean enableMEFluidBusCompat = true;

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (MMCEAddition.MODID.equals(event.getModID())) {
            ConfigManager.sync(MMCEAddition.MODID, Config.Type.INSTANCE);
        }
    }
}

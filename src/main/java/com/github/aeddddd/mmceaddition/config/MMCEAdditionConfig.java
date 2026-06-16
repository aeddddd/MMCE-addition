package com.github.aeddddd.mmceaddition.config;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge 配置文件类。
 * <p>
 * Forge 1.12.2 提供了基于注解的配置系统：
 * 用 {@link Config} 注解标记类，用 {@link Config.Name}、{@link Config.Comment}、
 * {@link Config.RangeInt} 等注解标记字段，Forge 会自动在 config/ 目录下生成 .cfg 文件，
 * 并在游戏内提供配置 GUI（Mods → 选择模组 → Config）。
 * <p>
 * {@link Mod.EventBusSubscriber} 让本类自动监听配置变更事件，
 * 玩家在游戏内修改配置后能立即同步到内存。
 */
@Config(modid = MMCEAddition.MODID)
@Config.LangKey("config.mmceaddition.title")
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID)
public class MMCEAdditionConfig {

    /**
     * 是否启用物品输出总线位置兼容。
     * <p>
     * 开启后，本模组的异步物品输出总线可以被 MMCE 机器结构识别为“任何原版物品输出总线位置”，
     * 从而无需修改机器 JSON 就可以替换原有总线。
     */
    @Config.Name("enable_me_item_bus_compat")
    @Config.Comment("允许异步 ME 物品输出总线替换原 MMCE 各类物品输出总线的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_me_item_bus_compat")
    public static boolean enableMEItemBusCompat = true;

    /**
     * 是否启用流体输出仓位置兼容。
     * <p>
     * 与上面类似，但针对流体输出仓。
     */
    @Config.Name("enable_me_fluid_bus_compat")
    @Config.Comment("允许异步 ME 流体输出仓替换原 MMCE 各类流体输出仓的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_me_fluid_bus_compat")
    public static boolean enableMEFluidBusCompat = true;

    /**
     * 异步输出注入间隔，单位：tick。
     * <p>
     * 缓冲区内的产出不会立即进入 ME 网络，而是每隔这么多 tick 批量注入一次。
     * 值越大，ME 网格承受的监听器触发次数越少，性能越好；
     * 值越小（最小为 1），产出进入网络的延迟越低。
     */
    @Config.Name("injection_interval")
    @Config.Comment("异步输出总线向 ME 网络批量注入的间隔（tick）。值越大，ME 网格压力越小，但产出进入网络的延迟越高。默认 5，最小 1，最大 1200。")
    @Config.RangeInt(min = 1, max = 1200)
    @Config.LangKey("config.mmceaddition.injection_interval")
    public static int injectionInterval = 5;

    /**
     * 配置变更事件处理器。
     * <p>
     * 当玩家在游戏内点击“Done”保存配置时，Forge 会触发 {@link ConfigChangedEvent.OnConfigChangedEvent}。
     * 调用 ConfigManager.sync 可以把文件中的最新值同步回这些静态字段，让运行时代码立即生效。
     *
     * @param event 配置变更事件
     */
    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (MMCEAddition.MODID.equals(event.getModID())) {
            ConfigManager.sync(MMCEAddition.MODID, Config.Type.INSTANCE);
        }
    }
}

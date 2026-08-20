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
     * 是否启用 ME 样板总成的结构位置兼容。
     * <p>
     * 开启后，本模组的 ME 样板总成可以被 MMCE 机器结构识别为原版物品/流体输入输出仓室
     * 以及 ME Pattern Provider 位置，从而无需修改机器 JSON 就可以替换这些结构位置。
     */
    @Config.Name("enable_me_pattern_assembly_compat")
    @Config.Comment("允许 ME 样板总成替换原 MMCE 各类物品/流体输入输出仓室及 ME Pattern Provider 的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_me_pattern_assembly_compat")
    public static boolean enableMEPatternAssemblyCompat = true;

    /**
     * 是否启用 ME 输出总成仓的结构位置兼容。
     * <p>
     * 开启后，ME 输出总成仓可以被 MMCE 机器结构识别为任意物品/流体/能源仓室位置
     * （含输入与输出），从而无需修改机器 JSON 就可以替换这些结构位置。
     */
    @Config.Name("enable_me_output_assembly_compat")
    @Config.Comment("允许 ME 输出总成仓替换原 MMCE 各类物品/流体/能源仓室（含输入与输出）的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_me_output_assembly_compat")
    public static boolean enableMEOutputAssemblyCompat = true;

    /**
     * 是否启用输入总成仓的结构位置兼容。
     * <p>
     * 开启后，输入总成仓可以被 MMCE 机器结构识别为任意物品/流体仓室位置
     * （含输入与输出），从而无需修改机器 JSON 就可以替换这些结构位置。
     */
    @Config.Name("enable_input_assembly_compat")
    @Config.Comment("允许输入总成仓替换原 MMCE 各类物品/流体仓室（含输入与输出）的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_input_assembly_compat")
    public static boolean enableInputAssemblyCompat = true;

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
     * 每批注入最多处理的异步仓室数量。
     * <p>
     * 如果世界中有大量异步输出仓室同时持有产出，一次注入 tick 处理太多可能导致单 tick 卡顿。
     * 这个上限把处理拆成多批；未处理完的仓室会保留在脏集合中等待下一批。
     * 值越大，单 tick 处理上限越高；设为 0 表示不限制。
     */
    @Config.Name("max_tiles_per_tick")
    @Config.Comment("每 tick 批量注入最多处理的异步仓室数量。0 表示不限制。若产出延迟严重可适当调高。默认 2000，最小 0，最大 100000。")
    @Config.RangeInt(min = 0, max = 100000)
    @Config.LangKey("config.mmceaddition.max_tiles_per_tick")
    public static int maxTilesPerTick = 2000;

    /**
     * 是否输出 ME 样板总成调试日志。
     * <p>
     * 开启后，控制台会打印 pushPattern、配方检查/开始/结束时的 slot 匹配信息，
     * 用于排查“缓冲区有物品但机器不执行配方”等问题。
     */
    @Config.Name("debug_pattern_assembly")
    @Config.Comment("输出 ME 样板总成调试日志（pushPattern、配方检查/开始/结束时的 slot 匹配）")
    @Config.LangKey("config.mmceaddition.debug_pattern_assembly")
    public static boolean debugPatternAssembly = false;

    /**
     * 是否启用伪并行迁移。
     * <p>
     * 开启后，自动把机器 JSON 中“同一 target 的 input ×N + output ×N”的伪并行 modifier 组
     * 转换为 MMCE 原生真实并行（按库存动态 1..N、per-tick 消耗同步缩放、随结构元件升级变化），
     * 无需修改任何机器 JSON。
     */
    @Config.Name("enable_fake_parallel_migration")
    @Config.Comment("自动把机器 JSON 中成对的输入×N/输出×N 伪并行 modifier 转换为原生真实并行（无需修改 JSON）")
    @Config.LangKey("config.mmceaddition.enable_fake_parallel_migration")
    public static boolean enableFakeParallelMigration = true;

    /**
     * 多档元件的并行度聚合策略。
     * <p>
     * max：取已安装最高档（替代式升级，推荐）；sum：所有已安装元件累加；product：连乘
     * （与原 modifier 连乘语义等价，极易爆炸，慎用）。
     */
    @Config.Name("fake_parallel_strategy")
    @Config.Comment("多档伪并行元件同时存在时的并行度聚合策略：max（取最高档，默认）/ sum（累加）/ product（连乘，与原 modifier 语义等价但易爆炸）")
    @Config.LangKey("config.mmceaddition.fake_parallel_strategy")
    public static String fakeParallelStrategy = "max";

    /**
     * 伪并行迁移机器黑名单。
     * <p>
     * 列表中的机器不会被迁移，保持原 JSON 行为。可写完整注册名（如 modularmachinery:xxx）
     * 或仅路径部分（如 mythic_processor_drying_rack）。
     */
    @Config.Name("fake_parallel_machine_blacklist")
    @Config.Comment("不参与伪并行迁移的机器注册名列表（完整名或路径均可）")
    @Config.LangKey("config.mmceaddition.fake_parallel_machine_blacklist")
    public static String[] fakeParallelMachineBlacklist = new String[0];

    /**
     * 是否输出伪并行迁移调试日志（每个元件摘除的 modifier 对数与倍率）。
     */
    @Config.Name("debug_fake_parallel_migration")
    @Config.Comment("输出伪并行迁移调试日志（逐元件的转换明细）")
    @Config.LangKey("config.mmceaddition.debug_fake_parallel_migration")
    public static boolean debugFakeParallelMigration = false;

    /**
     * 是否启用虚拟并行体系（虚拟装配台 + 机器数据 + 虚拟并行仓）。
     */
    @Config.Name("enable_virtual_parallel")
    @Config.Comment("启用虚拟并行体系：虚拟装配台把多方块机器装配为机器数据，放入虚拟并行仓提供独立乘区并行度")
    @Config.LangKey("config.mmceaddition.enable_virtual_parallel")
    public static boolean enableVirtualParallel = true;

    /**
     * 虚拟并行全局上限（int）。
     * <p>
     * 最终并行度 = 其他并行 × (1 + 虚拟并行仓并行)，再经本上限钳制。
     * 内部以 long 累乘防溢出；默认为 int 上限（即不限制），整合包可自行下调。
     */
    @Config.Name("virtual_parallel_cap")
    @Config.Comment("虚拟并行乘区的全局并行度上限（int 范围）。最终并行度经此钳制。默认 int 上限 = 不限制")
    @Config.RangeInt(min = 1, max = Integer.MAX_VALUE)
    @Config.LangKey("config.mmceaddition.virtual_parallel_cap")
    public static int virtualParallelCap = Integer.MAX_VALUE;

    /**
     * 虚拟并行机器黑名单：不生成机器数据、不提供虚拟并行。
     */
    @Config.Name("virtual_parallel_machine_blacklist")
    @Config.Comment("不参与虚拟并行的机器注册名列表（完整名或路径均可）：不可装配为机器数据，虚拟仓对其无效")
    @Config.LangKey("config.mmceaddition.virtual_parallel_machine_blacklist")
    public static String[] virtualParallelMachineBlacklist = new String[0];

    /**
     * 虚拟装配台缓存槽数量（每槽 int 上限；面向极大多方块，默认 216）。
     */
    @Config.Name("virtual_assembler_buffer_slots")
    @Config.Comment("虚拟装配台内部材料缓存槽数量（每槽计数上限为 int）")
    @Config.RangeInt(min = 27, max = 1024)
    @Config.LangKey("config.mmceaddition.virtual_assembler_buffer_slots")
    public static int virtualAssemblerBufferSlots = 216;

    /**
     * 是否允许虚拟并行仓替换机器 JSON 中任意仓室位置。
     */
    @Config.Name("enable_virtual_hatch_compat")
    @Config.Comment("允许虚拟并行仓替换原 MMCE 各类物品/流体/能源仓室及 ME Pattern Provider 的结构位置（无需修改机器 JSON）")
    @Config.LangKey("config.mmceaddition.enable_virtual_hatch_compat")
    public static boolean enableVirtualHatchCompat = true;

    /**
     * 是否输出虚拟并行调试日志。
     */
    @Config.Name("debug_virtual_parallel")
    @Config.Comment("输出虚拟并行调试日志（材料清单生成、虚拟系数计算）")
    @Config.LangKey("config.mmceaddition.debug_virtual_parallel")
    public static boolean debugVirtualParallel = false;

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

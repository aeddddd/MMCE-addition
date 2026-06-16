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
 * 模组主类（Mod Main Class）。
 * <p>
 * 在 Forge 1.12.2 中，每个模组都需要一个带有 {@link Mod} 注解的主类。
 * 这个类是 Forge 加载本模组的入口点，负责声明模组元数据、代理（Proxy）以及生命周期事件处理。
 * <p>
 * 本类同时实现了 {@link ILateMixinLoader}，这是 MixinBooter 提供的接口，
 * 用于在较晚的时机加载 Mixin 配置文件，确保目标类（如 MMCE 的 BlockArray）已经被类加载器加载。
 */
@Mod(
        // modid 是模组的唯一标识符，必须全部小写、无空格。
        // 它决定了资源路径（assets/<modid>/...）、配置文件名、NBT 标签前缀等。
        modid = MMCEAddition.MODID,
        // name 是显示在模组列表中的可读名称。
        name = MMCEAddition.NAME,
        // version 是模组版本号，会显示在模组列表里。
        version = MMCEAddition.VERSION,
        // dependencies 声明本模组的依赖关系。
        // required-after 表示必须在指定模组之后加载，否则游戏会崩溃并提示缺少依赖。
        dependencies = "required-after:modularmachinery;required-after:appliedenergistics2"
)
public class MMCEAddition implements ILateMixinLoader {

    /**
     * 模组 ID。所有资源文件、注册名、配置键都基于它。
     * 例如：方块注册名就是 MODID:block_name，语言键是 tile.MODID.block_name.name。
     */
    public static final String MODID = "mmceaddition";

    /**
     * 模组显示名称，用于 mcmod.info 和 Forge 模组列表界面。
     */
    public static final String NAME = "MMCE Addition";

    /**
     * 模组版本号。构建时 processResources 任务会把这里和 mcmod.info 里的占位符替换掉。
     */
    public static final String VERSION = "1.0-SNAPSHOT";

    /**
     * 客户端代理类的完整限定名。
     * Forge 的 @SidedProxy 会在运行时根据当前是客户端还是服务端选择对应的类。
     * 客户端代理负责模型、纹理、GUI 等只在客户端存在的内容。
     */
    public static final String CLIENT_PROXY = "com.github.aeddddd.mmceaddition.proxy.ClientProxy";

    /**
     * 公共/服务端代理类的完整限定名。
     * 服务端代理负责注册、命令、网络逻辑等两端通用或只在服务端运行的内容。
     */
    public static final String COMMON_PROXY = "com.github.aeddddd.mmceaddition.proxy.CommonProxy";

    /**
     * 模组单例实例。通过 @Mod.Instance 注解由 Forge 自动注入，方便其他类访问主类。
     */
    @Mod.Instance(MODID)
    public static MMCEAddition instance;

    /**
     *  sided proxy（分端代理）。
     * <p>
     * Forge 在启动时会根据当前运行端（物理客户端/物理服务端）创建对应类的实例：
     * <ul>
     *   <li>客户端：加载 ClientProxy，可以安全引用 net.minecraft.client 包下的类。</li>
     *   <li>服务端：加载 CommonProxy，不能引用客户端专用类，否则报 ClassNotFoundException。</li>
     * </ul>
     * 这是避免在服务端加载客户端代码的经典模式。
     */
    @SidedProxy(clientSide = CLIENT_PROXY, serverSide = COMMON_PROXY)
    public static CommonProxy proxy;

    /**
     * 预初始化阶段（Pre-Initialization）。
     * <p>
     * 这是 Forge 生命周期中最早的阶段，适合做：
     * <ul>
     *   <li>读取配置文件并同步</li>
     *   <li>注册事件监听器</li>
     *   <li>注册方块/物品/TileEntity（也可在 RegistryEvent 中注册）</li>
     *   <li>初始化网络/管理器</li>
     * </ul>
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    /**
     * 初始化阶段（Initialization）。
     * <p>
     * 在 PreInit 之后调用，适合：
     * <ul>
     *   <li>注册配方</li>
     *   <li>注册 GUI 处理器</li>
     *   <li>与其他模组进行交互</li>
     * </ul>
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    /**
     * 后初始化阶段（Post-Initialization）。
     * <p>
     * 在 Init 之后调用，适合：
     * <ul>
     *   <li>跨模组兼容收尾</li>
     *   <li>在几乎所有模组都加载完成后执行的逻辑</li>
     * </ul>
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    /**
     * 服务端启动事件。
     * <p>
     * 只在物理服务端（包括单人游戏的内部服务端）触发，适合注册服务器命令、初始化世界数据等。
     */
    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        proxy.onServerStarting(event);
    }

    /**
     * MixinBooter 要求的接口方法。
     * <p>
     * 返回本模组需要加载的 Mixin 配置文件路径列表。
     * MixinBooter 会在合适的时机把这些配置交给 Mixin 框架，实现对目标类的方法注入。
     *
     * @return Mixin 配置文件名列表
     */
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.mmceaddition.json");
    }
}

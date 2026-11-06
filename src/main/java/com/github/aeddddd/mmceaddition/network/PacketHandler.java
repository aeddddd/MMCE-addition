package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 网络包管理器。
 */
public class PacketHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(MMCEAddition.MODID);
    private static int id = 0;

    public static void register() {
        INSTANCE.registerMessage(PktMEPatternAssemblySelect.Handler.class,
                PktMEPatternAssemblySelect.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktMEPatternAssemblyScroll.Handler.class,
                PktMEPatternAssemblyScroll.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktMEPatternAssemblyBufferCounts.Handler.class,
                PktMEPatternAssemblyBufferCounts.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PktMEPatternAssemblyFluidBuffers.Handler.class,
                PktMEPatternAssemblyFluidBuffers.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PktMEPatternAssemblyReturn.Handler.class,
                PktMEPatternAssemblyReturn.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktVirtualAssemblerSelect.Handler.class,
                PktVirtualAssemblerSelect.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktVirtualAssemble.Handler.class,
                PktVirtualAssemble.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktVirtualAssemblerScroll.Handler.class,
                PktVirtualAssemblerScroll.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktVirtualAssemblerCounts.Handler.class,
                PktVirtualAssemblerCounts.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PktBufferViewScroll.Handler.class,
                PktBufferViewScroll.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktBufferViewClick.Handler.class,
                PktBufferViewClick.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktBufferViewReturn.Handler.class,
                PktBufferViewReturn.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktBufferViewSync.Handler.class,
                PktBufferViewSync.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PktHatchAdminRequest.Handler.class,
                PktHatchAdminRequest.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktHatchAdminAction.Handler.class,
                PktHatchAdminAction.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktHatchProfilerToggle.Handler.class,
                PktHatchProfilerToggle.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PktHatchAdminSync.Handler.class,
                PktHatchAdminSync.class, id++, Side.CLIENT);
    }
}

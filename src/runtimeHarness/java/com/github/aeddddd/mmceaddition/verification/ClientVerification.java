package com.github.aeddddd.mmceaddition.verification;

import com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly;
import com.github.aeddddd.mmceaddition.gui.GuiMEPatternAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.inventory.ClickType;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Opens the real server-backed GUI in an actual rendered client. */
@SideOnly(Side.CLIENT)
public final class ClientVerification {
    private static volatile boolean complete, finished;
    private int ticks, stage, changedAt;
    private boolean screenshot;
    public static void install() { MinecraftForge.EVENT_BUS.register(new ClientVerification()); }
    public static boolean done() { return complete; }
    public static void finish() { finished=true; }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getMinecraft(); ticks++;
        try {
            if (stage==0 && mc.currentScreen instanceof GuiMainMenu) {
                mc.gameSettings.pauseOnLostFocus=false; mc.gameSettings.guiScale=2;
                mc.gameSettings.renderDistanceChunks=2; mc.gameSettings.limitFramerate=60;
                mc.launchIntegratedServer("verification-world","MMCE Addition native AE2S acceptance",
                        new WorldSettings(5,GameType.CREATIVE,false,false,WorldType.FLAT).enableCommands());
                stage=1;
            }
            if (stage==1 && mc.world!=null && mc.player!=null && mc.currentScreen!=null) {
                mc.displayGuiScreen(null);
            }
            if (ticks%100==0 && !finished) System.out.println("Client acceptance stage="+stage+
                    " ready="+RuntimeVerification.readyForClient+" paused="+mc.isGamePaused()+
                    " screen="+(mc.currentScreen==null ? "none" : mc.currentScreen.getClass().getName()));
            if (stage==1 && RuntimeVerification.readyForClient && mc.world!=null && mc.player!=null) {
                mc.getIntegratedServer().addScheduledTask(() -> {
                    var server=mc.getIntegratedServer(); var player=server.getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
                    server.getWorld(0).setBlockState(new net.minecraft.util.math.BlockPos(8,80,3),net.minecraft.init.Blocks.STONE.getDefaultState(),3);
                    player.capabilities.isFlying=true; player.sendPlayerAbilities();
                    player.connection.setPlayerLocation(8.5,81,3.5,180,20);
                    var tile=(TileMEPatternAssembly)server.getWorld(0).getTileEntity(RuntimeVerification.PATTERN);
                    tile.openTerminalPatternContainerGui(player);
                });
                stage=2; changedAt=ticks;
            }
            if (stage==2 && ticks>changedAt+20 && mc.currentScreen instanceof GuiMEPatternAssembly) {
                RuntimeVerification.require(mc.player.openContainer instanceof ContainerMEPatternAssembly,"No server-backed assembly GUI");
                RuntimeVerification.require(mc.player.openContainer.inventorySlots.size()==120,"Incomplete GUI slots");
                RuntimeVerification.require(!mc.player.openContainer.getSlot(0).getStack().isEmpty(),"Native encoded pattern not synchronized");
                var missing=mc.getBlockRendererDispatcher().getBlockModelShapes().getModelManager().getMissingModel();
                for (var item : net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS) {
                    if (item.getRegistryName()!=null && item.getRegistryName().getNamespace().equals("mmceaddition")) {
                        RuntimeVerification.require(mc.getRenderItem().getItemModelMesher().getItemModel(new net.minecraft.item.ItemStack(item))!=missing,
                                "Missing item model: "+item.getRegistryName());
                    }
                }
                for (var block : net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS) {
                    if (block.getRegistryName()!=null && block.getRegistryName().getNamespace().equals("mmceaddition")) {
                        RuntimeVerification.require(mc.getBlockRendererDispatcher().getModelForState(block.getDefaultState())!=missing,
                                "Missing block model: "+block.getRegistryName());
                    }
                }
                screenshot=true; stage=3; changedAt=ticks;
            }
            if (stage==3 && !screenshot && ticks>changedAt+5) {
                mc.playerController.windowClick(mc.player.openContainer.windowId,0,0,ClickType.QUICK_MOVE,mc.player);
                stage=4; changedAt=ticks;
            }
            if (stage==4 && ticks>changedAt+20) {
                RuntimeVerification.require(mc.player.openContainer.getSlot(0).getStack().isEmpty(),"Shift click did not remove the encoded pattern");
                complete=true; stage=5;
            }
            if (finished) mc.shutdown();
            if (ticks>2200) throw new IllegalStateException("Client timed out in stage "+stage);
        } catch (Throwable failure) {
            failure.printStackTrace(); RuntimeVerification.clientFailure=failure.toString(); complete=true;
            if (finished) mc.shutdown();
        }
    }
    @SubscribeEvent public void render(TickEvent.RenderTickEvent event) {
        if (event.phase!=TickEvent.Phase.END || !screenshot) return;
        Minecraft mc=Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(mc.gameDir,"native-pattern-assembly.png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
        screenshot=false;
    }
}

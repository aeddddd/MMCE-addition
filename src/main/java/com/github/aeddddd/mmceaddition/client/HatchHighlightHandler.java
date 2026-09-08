package com.github.aeddddd.mmceaddition.client;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 仓室定位高亮渲染器。
 * <p>
 * 仓室管理工具 GUI 的"高亮"按钮把目标坐标加到这里，
 * 本类在世界渲染阶段为每个坐标绘制一个脉动的金色线框（持续 15 秒），
 * 帮助玩家在大型机器群中找到目标仓室。
 * 纯客户端状态，不持久化。
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID, value = Side.CLIENT)
public final class HatchHighlightHandler {

    private HatchHighlightHandler() {
    }

    /** 高亮持续时间（毫秒）。 */
    private static final long DURATION_MS = 15_000;

    /** 目标坐标 → 过期时间戳。仅客户端主线程访问。 */
    private static final Map<BlockPos, Long> MARKS = new HashMap<>();

    /** 添加一个高亮坐标（重复添加刷新持续时间）。 */
    public static void add(BlockPos pos) {
        MARKS.put(pos.toImmutable(), System.currentTimeMillis() + DURATION_MS);
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        if (MARKS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        MARKS.values().removeIf(expire -> expire < now);
        if (MARKS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        EntityPlayerSP player = mc.player;
        if (view == null || player == null) {
            return;
        }
        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        // 脉动透明度，便于在密集方块中辨认
        float pulse = 0.55F + 0.35F * (float) Math.sin(now / 150.0);

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(2.5F);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        for (Iterator<BlockPos> it = MARKS.keySet().iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            RenderGlobal.drawSelectionBoundingBox(
                    new AxisAlignedBB(pos).offset(-vx, -vy, -vz), 1.0F, 0.85F, 0.1F, pulse);
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }
}

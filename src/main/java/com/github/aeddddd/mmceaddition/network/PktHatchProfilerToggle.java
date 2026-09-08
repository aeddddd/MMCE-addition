package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.manager.HatchAdminManager;
import com.github.aeddddd.mmceaddition.manager.HatchProfiler;
import com.github.aeddddd.mmceaddition.manager.HatchType;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：切换仓室 tick 性能采样开关。
 * 切换后回发一份新的 {@link PktHatchAdminSync}（按耗时降序）刷新 GUI。
 */
public class PktHatchProfilerToggle implements IMessage {

    public boolean enabled;

    public PktHatchProfilerToggle() {
    }

    public PktHatchProfilerToggle(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enabled = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static class Handler implements IMessageHandler<PktHatchProfilerToggle, IMessage> {

        @Override
        public IMessage onMessage(PktHatchProfilerToggle message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                HatchProfiler.INSTANCE.setEnabled(message.enabled);
                player.sendMessage(new TextComponentTranslation(message.enabled
                        ? "message.mmceaddition.hatch_admin.profiler_on"
                        : "message.mmceaddition.hatch_admin.profiler_off"));
                PacketHandler.INSTANCE.sendTo(PktHatchAdminSync.build(player.getServerWorld(),
                        HatchType.ALL_MASK, HatchAdminManager.NET_ALL,
                        HatchAdminManager.SORT_TICK_TIME), player);
            });
            return null;
        }
    }
}

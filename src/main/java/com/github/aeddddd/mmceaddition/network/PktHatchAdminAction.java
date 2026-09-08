package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.manager.HatchAdminManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：批量操作（自动 IO 全开/全关、清空缓存）。
 * <p>
 * 操作作用于当前维度内匹配 {@link #typeMask} 的全部仓室。
 * 执行后回发一条聊天反馈与一份新的 {@link PktHatchAdminSync} 刷新 GUI。
 */
public class PktHatchAdminAction implements IMessage {

    /** 批量开启自动 IO。 */
    public static final int ACTION_IO_ON = 0;
    /** 批量关闭自动 IO。 */
    public static final int ACTION_IO_OFF = 1;
    /** 批量清空缓存。 */
    public static final int ACTION_CLEAR_BUFFERS = 2;

    public byte action;
    public int typeMask;

    public PktHatchAdminAction() {
    }

    public PktHatchAdminAction(int action, int typeMask) {
        this.action = (byte) action;
        this.typeMask = typeMask;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        typeMask = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeInt(typeMask);
    }

    public static class Handler implements IMessageHandler<PktHatchAdminAction, IMessage> {

        @Override
        public IMessage onMessage(PktHatchAdminAction message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                int count;
                String key;
                switch (message.action) {
                    case ACTION_IO_ON:
                        count = HatchAdminManager.applyAutoIO(player.getServerWorld(), message.typeMask, true);
                        key = "message.mmceaddition.hatch_admin.io_on";
                        break;
                    case ACTION_IO_OFF:
                        count = HatchAdminManager.applyAutoIO(player.getServerWorld(), message.typeMask, false);
                        key = "message.mmceaddition.hatch_admin.io_off";
                        break;
                    case ACTION_CLEAR_BUFFERS:
                    default:
                        count = HatchAdminManager.clearBuffers(player.getServerWorld(), message.typeMask);
                        key = "message.mmceaddition.hatch_admin.cleared";
                        break;
                }
                player.sendMessage(new TextComponentTranslation(key, count));
                // 操作后刷新 GUI 数据（保持类型筛选，网络过滤/排序复位为默认）
                PacketHandler.INSTANCE.sendTo(PktHatchAdminSync.build(player.getServerWorld(),
                        message.typeMask, HatchAdminManager.NET_ALL, HatchAdminManager.SORT_TYPE), player);
            });
            return null;
        }
    }
}

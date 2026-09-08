package com.github.aeddddd.mmceaddition.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：请求按筛选条件扫描仓室并回发 {@link PktHatchAdminSync}。
 * 服务端在玩家当前所在维度执行扫描。
 */
public class PktHatchAdminRequest implements IMessage {

    public int typeMask;
    public byte netFilter;
    public byte sortBy;

    public PktHatchAdminRequest() {
    }

    public PktHatchAdminRequest(int typeMask, int netFilter, int sortBy) {
        this.typeMask = typeMask;
        this.netFilter = (byte) netFilter;
        this.sortBy = (byte) sortBy;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        typeMask = buf.readInt();
        netFilter = buf.readByte();
        sortBy = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(typeMask);
        buf.writeByte(netFilter);
        buf.writeByte(sortBy);
    }

    public static class Handler implements IMessageHandler<PktHatchAdminRequest, IMessage> {

        @Override
        public IMessage onMessage(PktHatchAdminRequest message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                PktHatchAdminSync sync = PktHatchAdminSync.build(
                        player.getServerWorld(), message.typeMask, message.netFilter, message.sortBy);
                PacketHandler.INSTANCE.sendTo(sync, player);
            });
            return null;
        }
    }
}

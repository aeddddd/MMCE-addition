package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端通知服务端：缓冲面板的滚动偏移发生变化（鼠标滚轮）。
 * <p>
 * 与选中槽切换一样，服务端校验发送者确实打开了对应总成的 GUI 后才接受。
 */
public class PktMEPatternAssemblyScroll implements IMessage {

    private int offset;
    public BlockPos pos;

    public PktMEPatternAssemblyScroll() {
    }

    public PktMEPatternAssemblyScroll(int offset, BlockPos pos) {
        this.offset = offset;
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.offset = buf.readInt();
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(offset);
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
    }

    public static class Handler implements IMessageHandler<PktMEPatternAssemblyScroll, IMessage> {

        @Override
        public IMessage onMessage(PktMEPatternAssemblyScroll message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 校验：玩家必须正在与该位置的样板总成交互（GUI 打开中）
                if (!(player.openContainer instanceof ContainerMEPatternAssembly)) {
                    return;
                }
                ContainerMEPatternAssembly container = (ContainerMEPatternAssembly) player.openContainer;
                if (!container.getOwner().getPos().equals(message.pos)) {
                    return;
                }
                TileEntity tile = player.world.getTileEntity(message.pos);
                if (tile != container.getOwner()) {
                    return;
                }
                container.setBufferScrollOffset(message.offset);
            });
            return null;
        }
    }
}

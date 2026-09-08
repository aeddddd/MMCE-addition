package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerBufferView;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：缓冲视图 GUI 滚动请求（滚轮或拖动滚动条）。
 * 服务端钳制偏移后由常规窗口同步推送新内容。
 */
public class PktBufferViewScroll implements IMessage {

    private BlockPos pos;
    private int offset;

    public PktBufferViewScroll() {
    }

    public PktBufferViewScroll(BlockPos pos, int offset) {
        this.pos = pos;
        this.offset = offset;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
        offset = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeInt(offset);
    }

    public static class Handler implements IMessageHandler<PktBufferViewScroll, IMessage> {

        @Override
        public IMessage onMessage(PktBufferViewScroll message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!(player.openContainer instanceof ContainerBufferView)) {
                    return;
                }
                ContainerBufferView container = (ContainerBufferView) player.openContainer;
                // 校验发送者确实打开了该方块的 GUI
                if (!container.getPos().equals(message.pos)) {
                    return;
                }
                container.handleScroll(message.offset);
            });
            return null;
        }
    }
}

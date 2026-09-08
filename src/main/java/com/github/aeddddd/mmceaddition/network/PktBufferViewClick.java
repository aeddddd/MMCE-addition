package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerBufferView;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：缓冲视图 GUI 网格点击。
 * 具体存取语义（物品取出/存入、流体容器加注/汲出）由
 * {@link ContainerBufferView#handleCellClick} 服务端权威执行。
 */
public class PktBufferViewClick implements IMessage {

    private BlockPos pos;
    private int cell;
    private int button;
    private boolean shift;

    public PktBufferViewClick() {
    }

    public PktBufferViewClick(BlockPos pos, int cell, int button, boolean shift) {
        this.pos = pos;
        this.cell = cell;
        this.button = button;
        this.shift = shift;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
        cell = buf.readByte();
        button = buf.readByte();
        shift = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(cell);
        buf.writeByte(button);
        buf.writeBoolean(shift);
    }

    public static class Handler implements IMessageHandler<PktBufferViewClick, IMessage> {

        @Override
        public IMessage onMessage(PktBufferViewClick message, MessageContext ctx) {
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
                container.handleCellClick(message.cell, message.button, message.shift, player);
            });
            return null;
        }
    }
}

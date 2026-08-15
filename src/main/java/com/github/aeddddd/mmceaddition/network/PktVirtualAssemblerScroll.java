package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerVirtualAssembler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：虚拟装配台缓存槽可视窗口的滚动偏移变化（鼠标滚轮）。
 */
public class PktVirtualAssemblerScroll implements IMessage {

    private BlockPos pos;
    private int offset;

    public PktVirtualAssemblerScroll() {
    }

    public PktVirtualAssemblerScroll(BlockPos pos, int offset) {
        this.pos = pos;
        this.offset = offset;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        offset = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeInt(offset);
    }

    public static class Handler implements IMessageHandler<PktVirtualAssemblerScroll, IMessage> {

        @Override
        public IMessage onMessage(PktVirtualAssemblerScroll message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!(player.openContainer instanceof ContainerVirtualAssembler)) {
                    return;
                }
                ContainerVirtualAssembler container = (ContainerVirtualAssembler) player.openContainer;
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

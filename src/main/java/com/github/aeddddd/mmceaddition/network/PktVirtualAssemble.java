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
 * 客户端 → 服务端：请求虚拟装配台执行装配。
 *
 * @param maxRequested true = 按缓存材料装配最大份数；false = 装配 1 份
 */
public class PktVirtualAssemble implements IMessage {

    private BlockPos pos;
    private boolean maxRequested;

    public PktVirtualAssemble() {
    }

    public PktVirtualAssemble(BlockPos pos, boolean maxRequested) {
        this.pos = pos;
        this.maxRequested = maxRequested;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        maxRequested = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeBoolean(maxRequested);
    }

    public static class Handler implements IMessageHandler<PktVirtualAssemble, IMessage> {

        @Override
        public IMessage onMessage(PktVirtualAssemble message, MessageContext ctx) {
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
                String machine = container.getSelectedMachine();
                if (machine == null) {
                    return;
                }
                int k = message.maxRequested ? container.getOwner().getAssembleCount(machine) : 1;
                if (k > 0) {
                    container.getOwner().assemble(machine, k);
                }
            });
            return null;
        }
    }
}

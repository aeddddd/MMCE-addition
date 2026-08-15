package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerVirtualAssembler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：虚拟装配台选中了某台机器。
 * <p>
 * 服务端校验发送者确实打开了对应装配台的 GUI 后才接受（与样板总成一致）。
 */
public class PktVirtualAssemblerSelect implements IMessage {

    private BlockPos pos;
    private String machineName;

    public PktVirtualAssemblerSelect() {
    }

    public PktVirtualAssemblerSelect(BlockPos pos, String machineName) {
        this.pos = pos;
        this.machineName = machineName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        machineName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        ByteBufUtils.writeUTF8String(buf, machineName);
    }

    public static class Handler implements IMessageHandler<PktVirtualAssemblerSelect, IMessage> {

        @Override
        public IMessage onMessage(PktVirtualAssemblerSelect message, MessageContext ctx) {
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
                container.setSelectedMachine(message.machineName);
            });
            return null;
        }
    }
}

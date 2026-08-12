package com.github.aeddddd.mmceaddition.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端通知服务端：当前选中的样板槽索引发生变化（鼠标中键）。
 * <p>
 * 服务端处理时会校验发送者当前打开的容器确实是对应位置的样板总成，
 * 防止玩家在未打开 GUI 的情况下远程篡改任意总成的状态。
 */
public class PktMEPatternAssemblySelect implements IMessage {

    private int slotIndex;
    public BlockPos pos;

    public PktMEPatternAssemblySelect() {
    }

    public PktMEPatternAssemblySelect(int slotIndex, BlockPos pos) {
        this.slotIndex = slotIndex;
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.slotIndex = buf.readInt();
        this.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(slotIndex);
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
    }

    public static class Handler implements IMessageHandler<PktMEPatternAssemblySelect, IMessage> {

        @Override
        public IMessage onMessage(PktMEPatternAssemblySelect message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 校验：玩家必须正在与该位置的样板总成交互（GUI 打开中）
                if (!(player.openContainer instanceof com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly)) {
                    return;
                }
                com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly container =
                        (com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly) player.openContainer;
                if (!container.getOwner().getPos().equals(message.pos)) {
                    return;
                }
                World world = player.world;
                TileEntity tile = world.getTileEntity(message.pos);
                if (tile != container.getOwner()) {
                    return;
                }
                // 范围校验在 setSelectedSlot 内部完成
                container.setSelectedSlot(message.slotIndex);
            });
            return null;
        }
    }
}

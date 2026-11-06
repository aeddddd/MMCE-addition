package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerBufferView;
import com.github.aeddddd.mmceaddition.tile.TileInputAssembly;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 缓冲视图"返还至相邻接口"按钮请求（C → S）。
 * <p>
 * 仅对输入总成生效：把物品/流体缓存注入相邻 ME 接口/流体接口/二合一接口所在网络，
 * 结果经状态栏消息反馈给玩家。
 */
public class PktBufferViewReturn implements IMessage {

    private BlockPos pos;

    public PktBufferViewReturn() {
    }

    public PktBufferViewReturn(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
    }

    public static class Handler implements IMessageHandler<PktBufferViewReturn, IMessage> {
        @Override
        public IMessage onMessage(PktBufferViewReturn message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 校验发送者确实打开了该方块的缓冲视图 GUI
                if (!(player.openContainer instanceof ContainerBufferView)) {
                    return;
                }
                ContainerBufferView container = (ContainerBufferView) player.openContainer;
                if (!container.getPos().equals(message.pos)) {
                    return;
                }
                TileEntity tile = player.world.getTileEntity(message.pos);
                if (!(tile instanceof TileInputAssembly)) {
                    return;
                }
                long[] result = ((TileInputAssembly) tile).returnBuffersToAdjacentInterfaces();
                if (result == null) {
                    player.sendStatusMessage(
                            new TextComponentTranslation("gui.bufferview.return.none"), true);
                } else {
                    player.sendStatusMessage(
                            new TextComponentTranslation("gui.bufferview.return.done",
                                    result[0], result[1]), true);
                }
            });
            return null;
        }
    }
}

package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 样板总成"返还全部输入"按钮请求（C → S）。
 * <p>
 * 把全部样板槽输入缓存中的物品/流体返还到该总成所在的 ME 网络，
 * 结果经状态栏消息反馈给玩家。处理前校验发送者确实打开了该方块的 GUI，
 * 防止玩家在未打开界面时远程清空任意总成的输入缓存。
 */
public class PktMEPatternAssemblyReturn implements IMessage {

    private BlockPos pos;

    public PktMEPatternAssemblyReturn() {
    }

    public PktMEPatternAssemblyReturn(BlockPos pos) {
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

    public static class Handler implements IMessageHandler<PktMEPatternAssemblyReturn, IMessage> {
        @Override
        public IMessage onMessage(PktMEPatternAssemblyReturn message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!(player.openContainer instanceof ContainerMEPatternAssembly)) {
                    return;
                }
                ContainerMEPatternAssembly container = (ContainerMEPatternAssembly) player.openContainer;
                if (!container.getOwner().getPos().equals(message.pos)) {
                    return;
                }
                TileEntity tile = player.world.getTileEntity(message.pos);
                if (!(tile instanceof TileMEPatternAssembly) || tile != container.getOwner()) {
                    return;
                }
                TileMEPatternAssembly assembly = (TileMEPatternAssembly) tile;
                if (!assembly.hasAnyInput()) {
                    player.sendStatusMessage(
                            new TextComponentTranslation("gui.mepatternassembly.return.empty"), true);
                    return;
                }
                long[] result = assembly.returnAllInputsToNetwork();
                if (result == null) {
                    player.sendStatusMessage(
                            new TextComponentTranslation("gui.mepatternassembly.return.none"), true);
                } else if (result[0] == 0 && result[1] == 0) {
                    player.sendStatusMessage(
                            new TextComponentTranslation("gui.mepatternassembly.return.rejected"), true);
                } else {
                    player.sendStatusMessage(new TextComponentTranslation(
                            "gui.mepatternassembly.return.done", result[0], result[1]), true);
                }
            });
            return null;
        }
    }
}

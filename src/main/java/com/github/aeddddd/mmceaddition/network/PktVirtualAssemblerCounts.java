package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerVirtualAssembler;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 → 客户端：同步虚拟装配台缓存槽可视窗口的真实数量（int）。
 * <p>
 * ItemStack 网络序列化的数量为 byte，int 级数量通过本包单独同步，
 * 供 GUI 绘制大数量 overlay（与样板总成的 BufferCounts 包同一思路）。
 */
public class PktVirtualAssemblerCounts implements IMessage {

    private int[] counts;

    public PktVirtualAssemblerCounts() {
    }

    public PktVirtualAssemblerCounts(int[] counts) {
        this.counts = counts;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int len = buf.readByte();
        counts = new int[len];
        for (int i = 0; i < len; i++) {
            counts[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(counts.length);
        for (int count : counts) {
            buf.writeInt(count);
        }
    }

    public static class Handler implements IMessageHandler<PktVirtualAssemblerCounts, IMessage> {

        @Override
        public IMessage onMessage(PktVirtualAssemblerCounts message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().player == null) {
                    return;
                }
                if (Minecraft.getMinecraft().player.openContainer instanceof ContainerVirtualAssembler) {
                    ContainerVirtualAssembler container =
                            (ContainerVirtualAssembler) Minecraft.getMinecraft().player.openContainer;
                    container.getBufferView().setClientTrueCounts(message.counts);
                }
            });
            return null;
        }
    }
}

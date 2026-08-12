package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 → 客户端：同步当前选中样板槽输入/输出缓冲的真实数量（long）。
 * <p>
 * 缓冲槽的显示堆数量受 ItemStack 网络序列化限制被钳制到 maxStackSize，
 * 真实数量通过本包同步，供 GUI 绘制大数量 overlay 与 tooltip。
 */
public class PktMEPatternAssemblyBufferCounts implements IMessage {

    private long[] counts;

    public PktMEPatternAssemblyBufferCounts() {
    }

    public PktMEPatternAssemblyBufferCounts(long[] counts) {
        this.counts = counts;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int len = buf.readByte();
        counts = new long[len];
        for (int i = 0; i < len; i++) {
            counts[i] = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(counts.length);
        for (long count : counts) {
            buf.writeLong(count);
        }
    }

    public static class Handler implements IMessageHandler<PktMEPatternAssemblyBufferCounts, IMessage> {

        @Override
        public IMessage onMessage(PktMEPatternAssemblyBufferCounts message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().player == null) {
                    return;
                }
                if (Minecraft.getMinecraft().player.openContainer instanceof ContainerMEPatternAssembly) {
                    ContainerMEPatternAssembly container =
                            (ContainerMEPatternAssembly) Minecraft.getMinecraft().player.openContainer;
                    container.getBufferHandler().setClientTrueCounts(message.counts);
                }
            });
            return null;
        }
    }
}

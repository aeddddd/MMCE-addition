package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerBufferView;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：推送缓冲视图 GUI 当前滚动窗口的内容。
 * <p>
 * 网格条目（物品/流体 + Long 数量）不走原版槽位同步，
 * 服务端检测到可见窗口变化（内容/滚动/能源）时推送本包。
 */
public class PktBufferViewSync implements IMessage {

    private int scrollOffset;
    private int totalEntries;
    private long energy;
    private List<ContainerBufferView.Entry> entries;

    public PktBufferViewSync() {
    }

    public PktBufferViewSync(int scrollOffset, int totalEntries, long energy,
                             List<ContainerBufferView.Entry> entries) {
        this.scrollOffset = scrollOffset;
        this.totalEntries = totalEntries;
        this.energy = energy;
        this.entries = entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        scrollOffset = buf.readInt();
        totalEntries = buf.readInt();
        energy = buf.readLong();
        int count = buf.readByte();
        entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int type = buf.readByte();
            if (type == ContainerBufferView.Entry.ITEM) {
                ItemStack stack = ByteBufUtils.readItemStack(buf);
                long amount = buf.readLong();
                entries.add(ContainerBufferView.Entry.clientItem(stack, amount));
            } else {
                String name = ByteBufUtils.readUTF8String(buf);
                long amount = buf.readLong();
                Fluid fluid = FluidRegistry.getFluid(name);
                entries.add(fluid == null ? null : ContainerBufferView.Entry.clientFluid(fluid, amount));
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(scrollOffset);
        buf.writeInt(totalEntries);
        buf.writeLong(energy);
        buf.writeByte(entries.size());
        for (ContainerBufferView.Entry entry : entries) {
            buf.writeByte(entry.type);
            if (entry.type == ContainerBufferView.Entry.ITEM) {
                ByteBufUtils.writeItemStack(buf, entry.stack);
            } else {
                ByteBufUtils.writeUTF8String(buf, entry.fluid.getName());
            }
            buf.writeLong(entry.amount);
        }
    }

    public static class Handler implements IMessageHandler<PktBufferViewSync, IMessage> {

        @Override
        public IMessage onMessage(PktBufferViewSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().player == null) {
                    return;
                }
                if (Minecraft.getMinecraft().player.openContainer instanceof ContainerBufferView) {
                    ContainerBufferView container =
                            (ContainerBufferView) Minecraft.getMinecraft().player.openContainer;
                    container.setClientWindow(message.scrollOffset, message.totalEntries,
                            message.energy, message.entries);
                }
            });
            return null;
        }
    }
}

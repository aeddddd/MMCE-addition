package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.gui.ContainerMEPatternAssembly;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：同步当前选中样板槽的流体输入/输出缓冲内容。
 * <p>
 * 流体缓冲不参与 Container 的标准物品槽同步，内容（流体种类 + long 数量）
 * 通过本包在变化时全量推送，供 GUI 流体视图绘制。
 */
public class PktMEPatternAssemblyFluidBuffers implements IMessage {

    private String[] inputNames;
    private long[] inputAmounts;
    private String[] outputNames;
    private long[] outputAmounts;

    public PktMEPatternAssemblyFluidBuffers() {
    }

    public PktMEPatternAssemblyFluidBuffers(String[] inputNames, long[] inputAmounts,
                                            String[] outputNames, long[] outputAmounts) {
        this.inputNames = inputNames;
        this.inputAmounts = inputAmounts;
        this.outputNames = outputNames;
        this.outputAmounts = outputAmounts;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        inputNames = new String[buf.readByte()];
        inputAmounts = new long[inputNames.length];
        for (int i = 0; i < inputNames.length; i++) {
            inputNames[i] = ByteBufUtils.readUTF8String(buf);
            inputAmounts[i] = buf.readLong();
        }
        outputNames = new String[buf.readByte()];
        outputAmounts = new long[outputNames.length];
        for (int i = 0; i < outputNames.length; i++) {
            outputNames[i] = ByteBufUtils.readUTF8String(buf);
            outputAmounts[i] = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(inputNames.length);
        for (int i = 0; i < inputNames.length; i++) {
            ByteBufUtils.writeUTF8String(buf, inputNames[i]);
            buf.writeLong(inputAmounts[i]);
        }
        buf.writeByte(outputNames.length);
        for (int i = 0; i < outputNames.length; i++) {
            ByteBufUtils.writeUTF8String(buf, outputNames[i]);
            buf.writeLong(outputAmounts[i]);
        }
    }

    public static class Handler implements IMessageHandler<PktMEPatternAssemblyFluidBuffers, IMessage> {

        @Override
        public IMessage onMessage(PktMEPatternAssemblyFluidBuffers message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().player == null) {
                    return;
                }
                if (Minecraft.getMinecraft().player.openContainer instanceof ContainerMEPatternAssembly) {
                    ContainerMEPatternAssembly container =
                            (ContainerMEPatternAssembly) Minecraft.getMinecraft().player.openContainer;
                    container.setClientFluids(
                            toEntries(message.inputNames, message.inputAmounts),
                            toEntries(message.outputNames, message.outputAmounts));
                }
            });
            return null;
        }

        private static List<ContainerMEPatternAssembly.FluidBufferEntry> toEntries(String[] names, long[] amounts) {
            List<ContainerMEPatternAssembly.FluidBufferEntry> list = new ArrayList<>(names.length);
            for (int i = 0; i < names.length; i++) {
                Fluid fluid = FluidRegistry.getFluid(names[i]);
                if (fluid != null && amounts[i] > 0) {
                    list.add(new ContainerMEPatternAssembly.FluidBufferEntry(fluid, amounts[i]));
                }
            }
            return list;
        }
    }
}

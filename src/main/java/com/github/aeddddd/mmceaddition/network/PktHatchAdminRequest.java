package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.manager.HatchAdminManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：请求按筛选条件扫描仓室并回发 {@link PktHatchAdminSync}。
 * 服务端在玩家当前所在维度执行扫描。
 * <p>
 * 服务端处理前校验发送者手持仓室管理工具并做 20 tick 限流（扫描会遍历全部已加载区块），
 * 同时把类型掩码/网络过滤/排序钳制到合法取值；任一不满足即丢弃该包。
 */
public class PktHatchAdminRequest implements IMessage {

    public int typeMask;
    public byte netFilter;
    public byte sortBy;

    public PktHatchAdminRequest() {
    }

    public PktHatchAdminRequest(int typeMask, int netFilter, int sortBy) {
        this.typeMask = typeMask;
        this.netFilter = (byte) netFilter;
        this.sortBy = (byte) sortBy;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        typeMask = buf.readInt();
        netFilter = buf.readByte();
        sortBy = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(typeMask);
        buf.writeByte(netFilter);
        buf.writeByte(sortBy);
    }

    public static class Handler implements IMessageHandler<PktHatchAdminRequest, IMessage> {

        @Override
        public IMessage onMessage(PktHatchAdminRequest message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 服务端权威校验：只有手持仓室管理工具的玩家才能触发全维度扫描
                if (!HatchAdminManager.isHoldingAdminTool(player)) {
                    if (MMCEAdditionConfig.debugHatchAdmin) {
                        MMCEAddition.LOGGER.info("拒绝仓室扫描请求：player={} 未手持工具",
                                player.getName());
                    }
                    return;
                }
                // 限流：扫描要遍历全部已加载区块，间隔未到直接丢弃该包
                if (!HatchAdminManager.allowRate(player, HatchAdminManager.RATE_SCAN)) {
                    return;
                }
                PktHatchAdminSync sync = PktHatchAdminSync.build(player.getServerWorld(),
                        HatchAdminManager.sanitizeTypeMask(message.typeMask),
                        HatchAdminManager.sanitizeNetFilter(message.netFilter),
                        HatchAdminManager.sanitizeSortBy(message.sortBy));
                PacketHandler.INSTANCE.sendTo(sync, player);
            });
            return null;
        }
    }
}

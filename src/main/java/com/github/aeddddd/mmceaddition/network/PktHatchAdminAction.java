package com.github.aeddddd.mmceaddition.network;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.manager.HatchAdminManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务端：批量操作（自动 IO 全开/全关、清空缓存）。
 * <p>
 * 操作作用于当前维度内匹配 {@link #typeMask} 的全部仓室。
 * 执行后回发一条聊天反馈与一份新的 {@link PktHatchAdminSync} 刷新 GUI。
 * <p>
 * 服务端处理前依次校验：发送者手持仓室管理工具、未被限流、action 为三个合法值之一、
 * typeMask 只含已定义的类型位；任一不满足即丢弃该包。
 */
public class PktHatchAdminAction implements IMessage {

    /** 批量开启自动 IO。 */
    public static final int ACTION_IO_ON = 0;
    /** 批量关闭自动 IO。 */
    public static final int ACTION_IO_OFF = 1;
    /** 批量清空缓存。 */
    public static final int ACTION_CLEAR_BUFFERS = 2;

    public byte action;
    public int typeMask;

    public PktHatchAdminAction() {
    }

    public PktHatchAdminAction(int action, int typeMask) {
        this.action = (byte) action;
        this.typeMask = typeMask;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        typeMask = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeInt(typeMask);
    }

    public static class Handler implements IMessageHandler<PktHatchAdminAction, IMessage> {

        @Override
        public IMessage onMessage(PktHatchAdminAction message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                // 服务端权威校验：只有手持仓室管理工具的玩家才能执行批量操作
                if (!HatchAdminManager.isHoldingAdminTool(player)) {
                    if (MMCEAdditionConfig.debugHatchAdmin) {
                        MMCEAddition.LOGGER.info("拒绝仓室管理批量操作：player={} 未手持工具",
                                player.getName());
                    }
                    return;
                }
                // 限流：间隔未到直接丢弃该包（不回包、不执行）
                if (!HatchAdminManager.allowRate(player, HatchAdminManager.RATE_ACTION)) {
                    return;
                }
                // 类型掩码白名单钳制：越界位抹掉，抹成 0 即「不匹配任何仓室」
                int typeMask = HatchAdminManager.sanitizeTypeMask(message.typeMask);
                int count;
                String key;
                switch (message.action) {
                    case ACTION_IO_ON:
                        count = HatchAdminManager.applyAutoIO(player.getServerWorld(), typeMask, true);
                        key = "message.mmceaddition.hatch_admin.io_on";
                        break;
                    case ACTION_IO_OFF:
                        count = HatchAdminManager.applyAutoIO(player.getServerWorld(), typeMask, false);
                        key = "message.mmceaddition.hatch_admin.io_off";
                        break;
                    case ACTION_CLEAR_BUFFERS:
                        count = HatchAdminManager.clearBuffers(player.getServerWorld(), typeMask);
                        key = "message.mmceaddition.hatch_admin.cleared";
                        break;
                    default:
                        // 非法 action（含被伪造的任意字节）：拒绝执行，绝不落到任何分支的兜底
                        MMCEAddition.LOGGER.warn("拒绝非法的仓室管理操作 action={}（玩家 {}）",
                                message.action, player.getName());
                        return;
                }
                player.sendMessage(new TextComponentTranslation(key, count));
                // 操作后刷新 GUI 数据（保持类型筛选，网络过滤/排序复位为默认）
                PacketHandler.INSTANCE.sendTo(PktHatchAdminSync.build(player.getServerWorld(),
                        typeMask, HatchAdminManager.NET_ALL, HatchAdminManager.SORT_TYPE), player);
            });
            return null;
        }
    }
}

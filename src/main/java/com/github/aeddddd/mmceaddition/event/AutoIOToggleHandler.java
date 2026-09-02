package com.github.aeddddd.mmceaddition.event;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 自动 IO 开关切换事件处理器。
 * <p>
 * 手持 {@link MMCEAdditionConfig#autoIOToggleItems} 列表中的物品（默认各类扳手）
 * 右键 MMCE 的物品输入/输出总线或能源输入/输出仓时，切换该仓室的自动 IO 开关：
 * <ul>
 *   <li>取消右键事件，避免触发仓室本身的右键行为（如打开物品总线 GUI）；</li>
 *   <li>开关状态保存在 TileEntity NBT 中，由对应 Mixin 在 tick 逻辑头部门控。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID)
public final class AutoIOToggleHandler {

    private AutoIOToggleHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        TileEntity te = event.getWorld().getTileEntity(event.getPos());
        if (!(te instanceof IAutoIOControllable)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !isToggleItem(held)) {
            return;
        }
        // 阻止仓室本身的右键行为（物品总线会打开 GUI），两端都取消避免客户端假动作
        event.setCanceled(true);
        event.setCancellationResult(EnumActionResult.SUCCESS);
        if (event.getWorld().isRemote) {
            return;
        }
        IAutoIOControllable control = (IAutoIOControllable) te;
        boolean enabled = !control.mmceaddition$isAutoIOEnabled();
        control.mmceaddition$setAutoIOEnabled(enabled);
        te.markDirty();
        event.getEntityPlayer().sendStatusMessage(new TextComponentTranslation(
                enabled ? "message.mmceaddition.auto_io_on" : "message.mmceaddition.auto_io_off"), true);
    }

    /**
     * 判断物品是否命中配置的切换列表。
     * <p>
     * 列表条目格式：{@code modid:name}（任意 meta）或 {@code modid:name:meta}（指定 meta，* 同任意）。
     */
    private static boolean isToggleItem(ItemStack stack) {
        ResourceLocation registryName = stack.getItem().getRegistryName();
        if (registryName == null) {
            return false;
        }
        String id = registryName.toString();
        int meta = stack.getMetadata();
        for (String raw : MMCEAdditionConfig.autoIOToggleItems) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int firstColon = entry.indexOf(':');
            int lastColon = entry.lastIndexOf(':');
            if (firstColon < 0) {
                continue;
            }
            if (lastColon == firstColon) {
                // modid:name，任意 meta
                if (entry.equals(id)) {
                    return true;
                }
                continue;
            }
            // modid:name:meta
            if (!entry.regionMatches(0, id, 0, id.length()) || entry.charAt(id.length()) != ':') {
                continue;
            }
            String metaPart = entry.substring(lastColon + 1);
            if ("*".equals(metaPart)) {
                return true;
            }
            try {
                if (Integer.parseInt(metaPart) == meta) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // 非法 meta 条目，跳过
            }
        }
        return false;
    }
}

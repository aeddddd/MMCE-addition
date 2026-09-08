package com.github.aeddddd.mmceaddition.item;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.MMCEAdditionCreativeTab;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

/**
 * 仓室管理工具。
 * <p>
 * 手持右键打开全局仓室管理 GUI（MMCE 风格）：
 * <ul>
 *   <li>仓室列表：扫描当前维度已加载区块的全部 MMCE/本模组仓室，
 *       显示自动 IO、AE 网络状态、缓存量与 tick 耗时，支持筛选与定位高亮</li>
 *   <li>批量操作：自动 IO 全开/全关、清空缓存（按当前类型筛选生效）</li>
 *   <li>性能分析：仓室 tick 耗时采样开关 + 异步输出管理器运行统计</li>
 * </ul>
 * GUI 为纯客户端界面，所有数据与操作经网络包由服务端权威执行。
 */
public class ItemHatchAdminTool extends Item {

    public ItemHatchAdminTool() {
        setTranslationKey(MMCEAddition.MODID + ".hatch_admin_tool");
        setRegistryName(MMCEAddition.MODID, "hatch_admin_tool");
        setMaxStackSize(1);
        setCreativeTab(MMCEAdditionCreativeTab.TAB);
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(@Nonnull World world, EntityPlayer player,
                                                    @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) {
            // 纯客户端 GUI：直接打开，数据经网络包异步拉取
            MMCEAddition.proxy.openHatchAdminGui();
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}

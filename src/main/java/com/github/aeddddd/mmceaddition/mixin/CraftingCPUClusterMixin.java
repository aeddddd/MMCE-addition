package com.github.aeddddd.mmceaddition.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import net.minecraft.inventory.InventoryCrafting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 绕过 AE2 CPU 的发配限速（每 tick 最多 1 + 协处理器 次 pushPattern）。
 * <p>
 * AE2 的 {@code CraftingCPUCluster.executeCrafting} 每成功推送一份材料就把
 * {@code remainingOperations} 减一，额度耗尽后只能等下一 tick。
 * ME 样板总成的输入缓冲是无上限的 Long 缓冲、同配方材料本来就允许累积，
 * 限流对它毫无意义，只会让大额订单的发配被拆成几十上百次调度。
 * <p>
 * 这里在推送目标为样板总成且成功时退还本次消耗的额度（每 tick 退还次数有上限，
 * 防止异常情况下 CPU 发配循环无法退出），使同一 tick 内即可发配完整订单。
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterMixin {

    @Shadow
    private int remainingOperations;

    /** 每 tick 允许退还的调度额度上限。 */
    @Unique
    private static final int MMCEADDITION$BOOST_BUDGET = 4096;

    @Unique
    private int mmceaddition$boostBudget;

    @Inject(method = "updateCraftingLogic", at = @At("HEAD"))
    private void mmceaddition$resetBoostBudget(IGrid grid, IEnergyGrid eg, ICraftingLink link, CallbackInfo ci) {
        this.mmceaddition$boostBudget = MMCEADDITION$BOOST_BUDGET;
    }

    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingMedium;pushPattern("
                            + "Lappeng/api/networking/crafting/ICraftingPatternDetails;"
                            + "Lnet/minecraft/inventory/InventoryCrafting;)Z"))
    private boolean mmceaddition$pushPatternBoost(ICraftingMedium medium, ICraftingPatternDetails details,
                                                  InventoryCrafting table) {
        boolean accepted = medium.pushPattern(details, table);
        if (accepted && medium instanceof TileMEPatternAssembly && this.mmceaddition$boostBudget > 0) {
            // 退还本次发配消耗的调度额度，CPU 会在同一 tick 内继续推送剩余材料。
            this.mmceaddition$boostBudget--;
            this.remainingOperations++;
        }
        return accepted;
    }
}

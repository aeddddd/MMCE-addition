package com.github.aeddddd.mmceaddition.mixin.client;

import com.github.aeddddd.mmceaddition.util.FailureDetails;
import github.kasuminova.mmce.common.event.client.ControllerGUIRenderEvent;
import github.kasuminova.mmce.common.event.machine.MachineEvent;
import hellfirepvp.modularmachinery.common.crafting.helper.CraftingStatus;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 Mixin：向控制器 GUI 的 {@link ControllerGUIRenderEvent} 追加配方失败详情。
 * <p>
 * MMCE 的 {@code GuiMachineController} / {@code GuiFactoryController} 每帧构造
 * {@code ControllerGUIRenderEvent} 并调用 {@code postEvent()} 收集额外信息行，
 * 但该事件不投递到 Forge 事件总线（{@code MachineEvent.postEvent} 中已注释掉），
 * 无法直接订阅，因此在 {@code postEvent} 头部挂钩。
 * {@code ControllerGUIRenderEvent} 只由客户端 GUI 构造，此处使用客户端 I18n 是安全的。
 * <p>
 * 展示规则：状态不是工作中、且状态消息仍属于缓存的错误列表（防止展示过期详情）、
 * 且除状态行已显示的第一条错误外还有其他错误时，追加“失败详情”列表。
 */
@Mixin(value = MachineEvent.class, remap = false)
public abstract class ControllerGUIFailureDetailsMixin {

    @Inject(method = "postEvent()V", at = @At("HEAD"), remap = false)
    private void mmceaddition$appendFailureDetails(CallbackInfo ci) {
        if (!((Object) this instanceof ControllerGUIRenderEvent)) {
            return;
        }
        ControllerGUIRenderEvent event = (ControllerGUIRenderEvent) (Object) this;
        TileMultiblockMachineController controller = event.getController();
        if (controller == null) {
            return;
        }
        CraftingStatus status = controller.getControllerStatus();
        if (status == null || status.isCrafting()) {
            return;
        }
        List<String> details = FailureDetails.get(controller);
        if (details.size() <= 1) {
            // 单条错误与 GUI 原生状态行重复，无需追加
            return;
        }
        String statusMessage = status.getUnlocMessage();
        if (!details.contains(statusMessage)) {
            // 状态已切换（如红石停止/每 tick 失败），缓存详情已过期
            return;
        }

        List<String> lines = new ArrayList<>(details.size());
        lines.add(TextFormatting.RED + I18n.format("gui.mmceaddition.failure_details"));
        for (String detail : details) {
            if (detail.equals(statusMessage)) {
                // 第一条错误已由原生状态行显示
                continue;
            }
            lines.add(TextFormatting.GRAY + "- "
                    + (I18n.hasKey(detail) ? I18n.format(detail) : detail));
        }
        if (lines.size() > 1) {
            event.setExtraInfo(lines.toArray(new String[0]));
        }
    }
}

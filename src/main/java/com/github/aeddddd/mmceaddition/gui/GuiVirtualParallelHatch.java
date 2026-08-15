package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * 虚拟并行仓 GUI（AE 风格：浅灰底 + 蓝灰槽位）。
 * <p>
 * 显示数据槽、数据机器名、匹配状态与提供的并行度。
 * 匹配状态来自服务端同步（客户端 Tile 无法得知所属控制器）。
 */
public class GuiVirtualParallelHatch extends GuiContainer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(MMCEAddition.MODID, "textures/gui/virtual_parallel_hatch.png");

    private final TileVirtualParallelHatch tile;
    private final ContainerVirtualParallelHatch container2;

    public GuiVirtualParallelHatch(TileVirtualParallelHatch tile, ContainerVirtualParallelHatch container) {
        super(container);
        this.tile = tile;
        this.container2 = container;
        this.xSize = 176;
        this.ySize = 168;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mc.getTextureManager().bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("gui.virtualparallelhatch.title"), 8, 6, 0x404040);

        ItemStack data = tile.getDataStack();
        if (data.isEmpty()) {
            fontRenderer.drawString(I18n.format("gui.virtualparallelhatch.empty"), 8, 56, 0x808080);
        } else {
            String machineName = ItemMachineData.getMachineName(data);
            fontRenderer.drawString(I18n.format("gui.virtualparallelhatch.machine",
                    ItemMachineData.machineDisplayName(machineName)), 8, 56, 0x404040);
            if (container2.isMatchedSynced()) {
                // 有效并行贡献：有升级记录时 N = Σ升级贡献（替代机器数量），否则为机器数量
                hellfirepvp.modularmachinery.common.machine.DynamicMachine machine =
                        ItemMachineData.resolveMachine(machineName);
                long n = machine == null ? ItemMachineData.getCount(data)
                        : com.github.aeddddd.mmceaddition.virtual.VirtualParallelManager.effectiveParallelism(machine, data);
                fontRenderer.drawString(I18n.format("gui.virtualparallelhatch.active", n), 8, 66, 0x1B7A1B);
            } else {
                fontRenderer.drawString(I18n.format("gui.virtualparallelhatch.inactive"), 8, 66, 0xA03030);
            }
        }
        fontRenderer.drawString(I18n.format("gui.virtualparallelhatch.inventory"), 8, 73, 0x404040);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }
}

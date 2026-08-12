package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.network.PacketHandler;
import com.github.aeddddd.mmceaddition.network.PktMEPatternAssemblyScroll;
import com.github.aeddddd.mmceaddition.network.PktMEPatternAssemblySelect;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import org.lwjgl.input.Mouse;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.List;

/**
 * ME 样板总成客户端 GUI。
 * <p>
 * 视觉风格完全对齐 MMCE：直接复用 MMCE 的 ME 样板供应器纹理
 * （modularmachinery:textures/gui/mepatternprovider.png）。
 * 注意该纹理 y=175 以下是控件图集（箭头、槽框、按钮等），并非窗口边框，
 * 主窗口只能绘制 y&lt;175 的区域，底边边框取样自 y=175..180。
 * 催化剂面板位于窗口向下扩展的区域，扩展部分用主纹理的纯色区域做 9-patch 拼接，
 * 额外的槽位底纹直接复用纹理中样板格子的单元格（u=7, v=27, 18x18）。
 * <p>
 * 鼠标中键点击样板槽可切换右侧面板（输入/输出缓冲）与催化剂槽显示的目标槽位。
 * 缓冲槽显示堆数量受 maxStackSize 钳制，真实数量（long）以 overlay 形式绘制。
 */
@SideOnly(Side.CLIENT)
public class GuiMEPatternAssembly extends GuiContainer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            "modularmachinery", "textures/gui/mepatternprovider.png");

    private static final int GUI_WIDTH = 256;
    private static final int GUI_HEIGHT = 284;

    /**
     * 纹理布局（逐像素扫描确认）：
     * 快捷栏槽位底缘在 y=187，y=188 是底部高亮线，y=189..193 是底边边框，
     * y=194 起为控件图集（箭头、槽框等），不能绘制进窗口。
     * 右侧面板：凹槽列表区 x180..241 / y28..151，y=152 底部高亮，y=153..170 浅色条带，
     * y=171..188 有两个装饰死槽位（x179..212 与 x224..241），需覆盖。
     */
    private static final int MAIN_HEIGHT = 189;
    /** 底边边框在纹理中的位置与高度（y=189..193）。 */
    private static final int BORDER_V = 189;
    private static final int BORDER_HEIGHT = 5;

    /** 纹理中槽位单元格（含边框）的起点与尺寸。 */
    private static final int SLOT_U = 7;
    private static final int SLOT_V = 27;
    private static final int SLOT_SIZE = 18;

    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_SELECTED = 0x80FFFF00;

    private static final int BUFFER_START = ContainerMEPatternAssembly.PATTERNS + ContainerMEPatternAssembly.CATALYST_SLOTS;
    private static final int BUFFER_END = ContainerMEPatternAssembly.PLAYER_START;

    private final TileMEPatternAssembly assembly;
    private final ContainerMEPatternAssembly container;

    public GuiMEPatternAssembly(TileMEPatternAssembly assembly, ContainerMEPatternAssembly container) {
        super(container);
        this.assembly = assembly;
        this.container = container;
        this.xSize = GUI_WIDTH;
        this.ySize = GUI_HEIGHT;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(TEXTURE);

        // 主窗口（样板区、右侧缓冲面板、玩家背包），与 MMCE 样板供应器完全一致。
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, GUI_WIDTH, MAIN_HEIGHT);

        // 右侧面板底部的两个装饰死槽位（x179..242, y170..188）本 GUI 不使用，
        // 用列表凹槽同色的深灰覆盖，输出缓冲槽会从其上经过。
        drawScaledCustomSizeModalRect(guiLeft + 179, guiTop + 170, 185, 60, 20, 20, 63, 18, 256, 256);

        // 催化剂扩展面板：延续主窗口的左右两部分风格拼接
        // （左侧主面板浅灰 + 分隔线 + 右侧面板深灰），底边用纹理干净的边框条带。
        int extY = guiTop + MAIN_HEIGHT;
        int extHeight = GUI_HEIGHT - MAIN_HEIGHT - BORDER_HEIGHT;
        // 左边缘
        drawScaledCustomSizeModalRect(guiLeft, extY, 0, 100, 3, 18, 3, extHeight, 256, 256);
        // 主面板内部（浅灰）；取样条带限定在 y=100..108 的干净区域，
        // 再往下（y≈111+）是背包格子上沿，会被拉伸成槽框残影。
        drawScaledCustomSizeModalRect(guiLeft + 3, extY, 3, 100, 176, 8, 176, extHeight, 256, 256);
        // 主面板与右侧面板之间的分隔线（1:1 绘制，避免缩放糊边）
        drawScaledCustomSizeModalRect(guiLeft + 179, extY, 179, 100, 3, 18, 3, extHeight, 256, 256);
        // 右侧面板内部（深灰）
        drawScaledCustomSizeModalRect(guiLeft + 182, extY, 185, 60, 20, 20, 71, extHeight, 256, 256);
        // 右边缘
        drawScaledCustomSizeModalRect(guiLeft + 253, extY, 253, 100, 3, 18, 3, extHeight, 256, 256);

        // 底边边框（纹理 y=189..193 的干净条带）
        drawTexturedModalRect(guiLeft, guiTop + GUI_HEIGHT - BORDER_HEIGHT, 0, BORDER_V, GUI_WIDTH, BORDER_HEIGHT);

        // 催化剂槽与缓冲槽的底纹：复用纹理中的样板格子单元格
        for (int i = ContainerMEPatternAssembly.PATTERNS; i < ContainerMEPatternAssembly.PLAYER_START; i++) {
            Slot slot = container.inventorySlots.get(i);
            drawTexturedModalRect(guiLeft + slot.xPos - 1, guiTop + slot.yPos - 1, SLOT_U, SLOT_V, SLOT_SIZE, SLOT_SIZE);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("gui.mepatternassembly.title"), 7, 11, COLOR_TEXT);
        fontRenderer.drawString(I18n.format("gui.mepatternassembly.input"), 190, 10, COLOR_TEXT);
        fontRenderer.drawString(I18n.format("gui.mepatternassembly.output"), 190, 157, COLOR_TEXT);
        fontRenderer.drawString(I18n.format("gui.mepatternassembly.inventory"), 8, 103, COLOR_TEXT);
        fontRenderer.drawString(I18n.format("gui.mepatternassembly.catalyst"), 8, 220, COLOR_TEXT);

        // 高亮当前选中的样板槽
        int selected = container.getSelectedSlot();
        int selX = 8 + (selected % 9) * 18;
        int selY = 28 + (selected / 9) * 18;
        drawRect(selX, selY, selX + 16, selY + 16, COLOR_SELECTED);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        // 缓冲槽真实数量超过 maxStackSize 时，先把显示数量临时压成 1，
        // 抑制原版绘制的钳制角标（如 "64"），真实数量由 overlay 绘制。
        int[] patchedSlots = null;
        int[] patchedCounts = null;
        int patchCount = 0;
        for (int i = BUFFER_START; i < BUFFER_END; i++) {
            ItemStack stack = container.inventorySlots.get(i).getStack();
            if (!stack.isEmpty()
                    && container.getBufferHandler().getTrueCount(i - BUFFER_START) > stack.getMaxStackSize()) {
                if (patchedSlots == null) {
                    patchedSlots = new int[BUFFER_END - BUFFER_START];
                    patchedCounts = new int[BUFFER_END - BUFFER_START];
                }
                patchedSlots[patchCount] = i;
                patchedCounts[patchCount] = stack.getCount();
                stack.setCount(1);
                patchCount++;
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (patchedSlots != null) {
            for (int i = 0; i < patchCount; i++) {
                container.inventorySlots.get(patchedSlots[i]).getStack().setCount(patchedCounts[i]);
            }
        }

        drawBufferCountOverlays();
        drawBufferScrollbar();
        renderHoveredToolTip(mouseX, mouseY);
    }

    /** 缓冲面板右侧的滚动指示条：仅在内容超出一页时显示。 */
    private void drawBufferScrollbar() {
        int max = container.getMaxBufferScrollOffset();
        if (max <= 0) {
            return;
        }
        int trackX = guiLeft + 244;
        int trackY = guiTop + 30;
        int trackH = 248;
        drawRect(trackX, trackY, trackX + 3, trackY + trackH, 0xFF1A1A1A);
        int visible = SelectedSlotBufferHandler.SLOTS_PER_BUFFER;
        int thumbH = Math.max(12, trackH * visible / (max + visible));
        int thumbY = trackY + (trackH - thumbH) * container.getBufferScrollOffset() / max;
        drawRect(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFC6C6C6);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        // 仅当鼠标悬停在右侧缓冲面板上时滚动缓冲视图
        if (mouseX < guiLeft + 182 || mouseX >= guiLeft + 253
                || mouseY < guiTop + 8 || mouseY >= guiTop + GUI_HEIGHT - BORDER_HEIGHT) {
            return;
        }
        int current = container.getBufferScrollOffset();
        int max = container.getMaxBufferScrollOffset();
        // 每次滚动一行（3 个变体）
        int next = Math.max(0, Math.min(max, current + (wheel > 0 ? -3 : 3)));
        if (next != current) {
            container.setBufferScrollOffset(next);
            PacketHandler.INSTANCE.sendToServer(new PktMEPatternAssemblyScroll(next, assembly.getPos()));
        }
    }

    /**
     * 在缓冲槽右下角绘制真实数量（0.5 倍缩放）。
     * 仅当真实数量超过显示上限（maxStackSize）时绘制，否则原版角标已经准确。
     */
    private void drawBufferCountOverlays() {
        for (int i = BUFFER_START; i < BUFFER_END; i++) {
            Slot slot = container.inventorySlots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            long trueCount = container.getBufferHandler().getTrueCount(i - BUFFER_START);
            if (trueCount <= stack.getMaxStackSize()) {
                continue;
            }
            String text = formatCount(trueCount);
            GlStateManager.pushMatrix();
            GlStateManager.translate(guiLeft + slot.xPos, guiTop + slot.yPos, 300.0F);
            GlStateManager.scale(0.5F, 0.5F, 1.0F);
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            fontRenderer.drawStringWithShadow(text, 32 - fontRenderer.getStringWidth(text), 24, 0xFFFFFF);
            GlStateManager.enableDepth();
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        }
    }

    /** 大数量的紧凑格式化：一万以内精确显示，超出用 k/M/G 后缀。 */
    private static String formatCount(long count) {
        if (count < 10000L) {
            return Long.toString(count);
        }
        if (count < 1000000L) {
            return (count / 1000L) + "k";
        }
        if (count < 1000000000L) {
            return (count / 1000000L) + "M";
        }
        return (count / 1000000000L) + "G";
    }

    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        Slot hovered = getSlotUnderMouse();
        if (hovered instanceof ContainerMEPatternAssembly.BufferSlot && hovered.getHasStack()) {
            ItemStack stack = hovered.getStack();
            long trueCount = container.getBufferHandler().getTrueCount(
                    hovered.slotNumber - BUFFER_START);
            if (trueCount > stack.getMaxStackSize()) {
                List<String> tooltip = stack.getTooltip(mc.player, mc.gameSettings.advancedItemTooltips
                        ? ITooltipFlag.TooltipFlags.ADVANCED : ITooltipFlag.TooltipFlags.NORMAL);
                tooltip.add(1, I18n.format("gui.mepatternassembly.count", trueCount));
                FontRenderer font = stack.getItem().getFontRenderer(stack);
                drawHoveringText(tooltip, mouseX, mouseY, font == null ? fontRenderer : font);
                return;
            }
        }
        super.renderHoveredToolTip(mouseX, mouseY);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 2) { // 鼠标中键：切换右侧面板/催化剂显示的目标样板槽
            Slot hovered = getSlotUnderMouse();
            // 注意：不能用 slot.inventory 判断（SlotItemHandler 的 inventory 是共享空壳），
            // 这里用 slotNumber（槽在容器中的序号），样板槽固定为前 36 个。
            if (hovered != null && hovered.slotNumber >= 0
                    && hovered.slotNumber < ContainerMEPatternAssembly.PATTERNS) {
                int slotIndex = hovered.slotNumber;
                container.setSelectedSlot(slotIndex);
                PacketHandler.INSTANCE.sendToServer(
                        new PktMEPatternAssemblySelect(slotIndex, assembly.getPos()));
            }
        }
    }
}

package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.gui.ContainerBufferView.Entry;
import com.github.aeddddd.mmceaddition.network.PacketHandler;
import com.github.aeddddd.mmceaddition.network.PktBufferViewClick;
import com.github.aeddddd.mmceaddition.network.PktBufferViewReturn;
import com.github.aeddddd.mmceaddition.network.PktBufferViewScroll;
import com.github.aeddddd.mmceaddition.tile.TileInputAssembly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * 缓冲视图 GUI（MMCE 风格：#C6C6C6 底、黑白斜面描边、#8B8B8B 槽位）。
 * <p>
 * 布局（194x198，纹理 buffer_view.png）：
 * <ul>
 *   <li>缓冲网格 9x4：(7,17) 起，物品/流体条目混排，滚轮或右侧滚动条翻页</li>
 *   <li>滚动条轨道：(174,17,12x72)，把手贴图 (196,0,12x15)</li>
 *   <li>信息行 y=95（能源或空提示），背包标签 y=104</li>
 *   <li>玩家背包 (8,116) + 快捷栏 (8,174)</li>
 * </ul>
 * 网格不是原版槽位：内容由服务端窗口包推送，点击/滚动经自定义包上行。
 */
public class GuiBufferView extends GuiContainer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(MMCEAddition.MODID, "textures/gui/buffer_view.png");

    /** 网格条目渲染起点（cell border 在 (7,17)，内容内缩 1px）。 */
    private static final int GRID_X = 8, GRID_Y = 18;
    /** 滚动条轨道与把手。 */
    private static final int TRACK_X = 174, TRACK_Y = 17, TRACK_W = 12, TRACK_H = 72, HANDLE_H = 15;

    private final TileEntity tile;
    private final ContainerBufferView container2;

    /** 是否正在拖动滚动条把手。 */
    private boolean dragging = false;
    /** 上次已发送的滚动偏移，避免拖动时重复发包。 */
    private int lastSentScroll = -1;
    /** "返还至相邻接口"按钮，仅输入总成显示。 */
    private GuiButton returnButton;

    public GuiBufferView(TileEntity tile, ContainerBufferView container) {
        super(container);
        this.tile = tile;
        this.container2 = container;
        this.xSize = 194;
        this.ySize = 198;
    }

    @Override
    public void initGui() {
        super.initGui();
        if (tile instanceof TileInputAssembly) {
            returnButton = new GuiButton(0, guiLeft + 102, guiTop + 91, 84, 14,
                    I18n.format("gui.bufferview.return"));
            buttonList.add(returnButton);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == returnButton) {
            PacketHandler.INSTANCE.sendToServer(new PktBufferViewReturn(container2.getPos()));
        }
    }

    // ==================== 绘制 ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mc.getTextureManager().bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

        // 滚动条把手（内容不超一页时置顶）
        int maxOffset = ContainerBufferView.maxScrollOffset(container2.getTotalEntries());
        int handleY = guiTop + TRACK_Y;
        if (maxOffset > 0) {
            handleY += (TRACK_H - HANDLE_H) * container2.getScrollOffset() / maxOffset;
        }
        drawTexturedModalRect(guiLeft + TRACK_X, handleY, 196, 0, TRACK_W, HANDLE_H);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format(tile.getBlockType().getTranslationKey() + ".name"), 8, 6, 0x404040);
        if (container2.hasViewEnergy()) {
            fontRenderer.drawString(I18n.format("gui.bufferview.energy", container2.getEnergy()), 8, 95, 0x404040);
        }
        if (container2.getTotalEntries() == 0) {
            String empty = I18n.format("gui.bufferview.empty");
            fontRenderer.drawString(empty, GRID_X + (ContainerBufferView.COLS * 18 - fontRenderer.getStringWidth(empty)) / 2,
                    GRID_Y + 30, 0xFFFFFF);
        }
        fontRenderer.drawString(I18n.format("gui.bufferview.inventory"), 8, 104, 0x404040);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        GlStateManager.pushMatrix();
        GlStateManager.translate(guiLeft, guiTop, 0);
        drawEntries();
        GlStateManager.popMatrix();
        renderHoveredToolTip(mouseX, mouseY);
        if (returnButton != null && returnButton.isMouseOver()) {
            drawHoveringText(I18n.format("gui.bufferview.return.tooltip"), mouseX, mouseY);
        }
    }

    /** 网格条目：物品图标或流体图标 + Long 数量角标（0.5 倍缩放右下角）。 */
    private void drawEntries() {
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        for (int i = 0; i < ContainerBufferView.VISIBLE; i++) {
            Entry entry = container2.getClientEntry(i);
            if (entry == null) {
                continue;
            }
            int x = GRID_X + (i % ContainerBufferView.COLS) * 18;
            int y = GRID_Y + (i / ContainerBufferView.COLS) * 18;
            if (entry.type == Entry.ITEM) {
                itemRender.renderItemAndEffectIntoGUI(mc.player, entry.stack, x, y);
            } else {
                drawFluidIcon(x, y, entry);
            }
            drawAmount(x, y, entry.amount);
        }
        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
    }

    private void drawFluidIcon(int x, int y, Entry entry) {
        TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(entry.fluid.getStill().toString());
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        int color = entry.fluid.getColor();
        GlStateManager.color(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, 1.0F);
        drawTexturedModalRect(x, y, sprite, 16, 16);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawAmount(int x, int y, long amount) {
        String text = formatCount(amount);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 300.0F);
        GlStateManager.scale(0.5F, 0.5F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        fontRenderer.drawStringWithShadow(text, 32 - fontRenderer.getStringWidth(text), 24, 0xFFFFFF);
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        super.renderHoveredToolTip(mouseX, mouseY);
        int cell = cellAt(mouseX - guiLeft, mouseY - guiTop);
        if (cell < 0) {
            return;
        }
        Entry entry = container2.getClientEntry(cell);
        if (entry == null) {
            return;
        }
        if (entry.type == Entry.ITEM) {
            List<String> tooltip = entry.stack.getTooltip(mc.player,
                    mc.gameSettings.advancedItemTooltips
                            ? net.minecraft.client.util.ITooltipFlag.TooltipFlags.ADVANCED
                            : net.minecraft.client.util.ITooltipFlag.TooltipFlags.NORMAL);
            tooltip.add(1, I18n.format("gui.mepatternassembly.count", entry.amount));
            drawHoveringText(tooltip, mouseX, mouseY);
        } else {
            java.util.List<String> tooltip = new java.util.ArrayList<>();
            tooltip.add(entry.fluid.getLocalizedName(new FluidStack(entry.fluid, 1)));
            tooltip.add(I18n.format("gui.mepatternassembly.fluidcount", entry.amount));
            tooltip.add(I18n.format("gui.bufferview.fluid_hint"));
            drawHoveringText(tooltip, mouseX, mouseY);
        }
    }

    /** 命中检测：返回网格格号，未命中返回 -1。 */
    private static int cellAt(int relX, int relY) {
        int col = (relX - (GRID_X - 1)) / 18;
        int row = (relY - (GRID_Y - 1)) / 18;
        if (col < 0 || col >= ContainerBufferView.COLS || row < 0 || row >= ContainerBufferView.ROWS) {
            return -1;
        }
        return row * ContainerBufferView.COLS + col;
    }

    // ==================== 交互 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int relX = mouseX - guiLeft, relY = mouseY - guiTop;
        // 滚动条：按下开始拖动
        if (relX >= TRACK_X && relX < TRACK_X + TRACK_W && relY >= TRACK_Y && relY < TRACK_Y + TRACK_H) {
            dragging = true;
            updateDrag(mouseY);
            return;
        }
        // 网格点击：发包给服务端权威处理
        int cell = cellAt(relX, relY);
        if (cell >= 0) {
            PacketHandler.INSTANCE.sendToServer(
                    new PktBufferViewClick(container2.getPos(), cell, mouseButton, isShiftKeyDown()));
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (dragging) {
            updateDrag(mouseY);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    /** 拖动把手：按鼠标高度换算行偏移并发送滚动包。 */
    private void updateDrag(int mouseY) {
        int maxOffset = ContainerBufferView.maxScrollOffset(container2.getTotalEntries());
        if (maxOffset <= 0) {
            return;
        }
        float rel = (mouseY - guiTop - TRACK_Y - HANDLE_H / 2.0f) / (TRACK_H - HANDLE_H);
        int row = Math.round(rel * (maxOffset / ContainerBufferView.COLS));
        int offset = MathHelper.clamp(row * ContainerBufferView.COLS, 0, maxOffset);
        if (offset != lastSentScroll) {
            lastSentScroll = offset;
            PacketHandler.INSTANCE.sendToServer(new PktBufferViewScroll(container2.getPos(), offset));
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth - guiLeft;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1 - guiTop;
        boolean overGrid = cellAt(mouseX, mouseY) >= 0;
        boolean overTrack = mouseX >= TRACK_X && mouseX < TRACK_X + TRACK_W
                && mouseY >= TRACK_Y && mouseY < TRACK_Y + TRACK_H;
        if (!overGrid && !overTrack) {
            return;
        }
        int maxOffset = ContainerBufferView.maxScrollOffset(container2.getTotalEntries());
        if (maxOffset <= 0) {
            return;
        }
        int offset = container2.getScrollOffset() + (wheel > 0 ? -1 : 1) * ContainerBufferView.COLS;
        offset = MathHelper.clamp(offset, 0, maxOffset);
        if (offset != container2.getScrollOffset()) {
            PacketHandler.INSTANCE.sendToServer(new PktBufferViewScroll(container2.getPos(), offset));
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000_000L) {
            return (count / 1_000_000_000L) + "G";
        }
        if (count >= 1_000_000L) {
            return (count / 1_000_000L) + "M";
        }
        if (count >= 10_000L) {
            return (count / 1_000L) + "k";
        }
        return String.valueOf(count);
    }
}

package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.network.PacketHandler;
import com.github.aeddddd.mmceaddition.network.PktVirtualAssemble;
import com.github.aeddddd.mmceaddition.network.PktVirtualAssemblerScroll;
import com.github.aeddddd.mmceaddition.network.PktVirtualAssemblerSelect;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.MachineMaterialAnalyzer;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.MachineRegistry;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟装配台 GUI（AE 风格：浅灰底、蓝灰面板、深蓝描边）。
 * <p>
 * 布局（248x252）：
 * <ul>
 *   <li>左上：搜索框 (7,15) + 机器列表 (7,29,102x72，7 行，滚轮翻页，点击选中)</li>
 *   <li>右上：材料网格 (115,29,128x72，7 列 x 4 行，多选一候选轮显)，
 *       可装配份数与装配按钮 (170,104)，输出槽 (224,104)</li>
 *   <li>下方：材料缓存 9x2 (8,126，滚轮翻页) + 玩家背包 (8,176)</li>
 * </ul>
 * 所有自定义绘制都在前景层进行（背景层只画底图），避免被槽位绘制覆盖。
 */
public class GuiVirtualAssembler extends GuiContainer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(MMCEAddition.MODID, "textures/gui/virtual_assembler.png");

    /** 机器列表区域 */
    private static final int LIST_X = 8, LIST_Y = 30, LIST_W = 100, LIST_ROWS = 7, ROW_H = 10;
    /** 材料网格区域：7 列 x 4 行 */
    private static final int MAT_X = 116, MAT_Y = 30, MAT_COLS = 7, MAT_ROWS = 4;
    /** 缓存槽区域（与容器槽位坐标一致） */
    private static final int BUF_X = 8, BUF_Y = 126, BUF_COLS = 9, BUF_ROWS = 2;

    private final ContainerVirtualAssembler container;

    private GuiTextField searchField;
    private GuiButton assembleButton;

    /** 全部可装配机器（GUI 打开时快照）。 */
    private final List<DynamicMachine> allMachines = new ArrayList<>();
    /** 搜索过滤后的机器列表。 */
    private List<DynamicMachine> filteredMachines = new ArrayList<>();

    private int machineScroll = 0;
    private int materialScroll = 0;

    public GuiVirtualAssembler(ContainerVirtualAssembler container) {
        super(container);
        this.container = container;
        this.xSize = 248;
        this.ySize = 252;
    }

    @Override
    public void initGui() {
        super.initGui();
        searchField = new GuiTextField(0, fontRenderer, guiLeft + LIST_X, guiTop + 16, LIST_W, 11);
        searchField.setCanLoseFocus(true);
        searchField.setFocused(false);
        assembleButton = new GuiButton(0, guiLeft + 182, guiTop + 104, 40, 16,
                I18n.format("gui.virtualassembler.assemble"));
        buttonList.add(assembleButton);

        // 机器清单快照：过滤黑名单与无材料清单的机器
        allMachines.clear();
        for (DynamicMachine machine : MachineRegistry.getLoadedMachines()) {
            if (machine == null || MachineMaterialAnalyzer.isBlacklisted(machine)) {
                continue;
            }
            if (MachineMaterialAnalyzer.analyze(machine).isEmpty()) {
                continue;
            }
            allMachines.add(machine);
        }
        allMachines.sort((a, b) -> a.getRegistryName().toString().compareTo(b.getRegistryName().toString()));
        applyFilter();

        // 打开时若无选中机器，自动选中列表第一台（本地设置 + 发包服务端）
        if (container.getSelectedMachine() == null && !filteredMachines.isEmpty()) {
            selectMachine(filteredMachines.get(0));
        }
    }

    private void applyFilter() {
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase();
        filteredMachines = new ArrayList<>();
        for (DynamicMachine machine : allMachines) {
            if (query.isEmpty()
                    || machine.getRegistryName().toString().toLowerCase().contains(query)
                    || (machine.getLocalizedName() != null && machine.getLocalizedName().toLowerCase().contains(query))) {
                filteredMachines.add(machine);
            }
        }
        machineScroll = 0;
    }

    private void selectMachine(DynamicMachine machine) {
        String name = machine.getRegistryName().toString();
        container.setSelectedMachine(name);
        PacketHandler.INSTANCE.sendToServer(
                new PktVirtualAssemblerSelect(container.getOwner().getPos(), name));
        materialScroll = 0;
    }

    private DynamicMachine selectedMachine() {
        String name = container.getSelectedMachine();
        return name == null ? null : ItemMachineData.resolveMachine(name);
    }

    // ==================== 绘制 ====================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mc.getTextureManager().bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
        // 搜索框使用绝对坐标，必须在无平移的背景层绘制（前景层带 guiLeft/guiTop 平移会双重偏移）
        searchField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("gui.virtualassembler.title"), 8, 6, 0x404040);
        drawMachineList();
        drawMaterialGrid();

        fontRenderer.drawString(I18n.format("gui.virtualassembler.buffer"), BUF_X, BUF_Y - 10, 0x404040);
        fontRenderer.drawString(I18n.format("gui.virtualassembler.inventory"), 8, 166, 0x404040);

        // 可装配份数（服务端同步；32767 为同步上限，显示为 32767+）
        int k = container.getAssembleCount();
        String info = I18n.format("gui.virtualassembler.available", k) + (k >= 32767 ? "+" : "");
        fontRenderer.drawString(info, MAT_X, 108, k > 0 ? 0x1B7A1B : 0x808080);
    }

    private void drawMachineList() {
        if (filteredMachines.isEmpty()) {
            fontRenderer.drawString(I18n.format("gui.virtualassembler.no_machines"), LIST_X + 2, LIST_Y + 1, 0xFFFFFF);
            return;
        }
        for (int i = 0; i < LIST_ROWS; i++) {
            int index = machineScroll + i;
            if (index >= filteredMachines.size()) {
                break;
            }
            DynamicMachine machine = filteredMachines.get(index);
            int rowY = LIST_Y + i * ROW_H;
            boolean selected = machine.getRegistryName().toString().equals(container.getSelectedMachine());
            if (selected) {
                drawRect(LIST_X, rowY, LIST_X + LIST_W, rowY + ROW_H, 0xFF6A6AC8);
            }
            String name = machine.getLocalizedName() != null && !machine.getLocalizedName().isEmpty()
                    ? machine.getLocalizedName() : machine.getRegistryName().getPath();
            name = fontRenderer.trimStringToWidth(name, LIST_W - 4);
            fontRenderer.drawString(name, LIST_X + 2, rowY + 1, selected ? 0xFFFFFF : 0x1B1B2E);
        }
    }

    private void drawMaterialGrid() {
        DynamicMachine machine = selectedMachine();
        if (machine == null) {
            return;
        }
        List<MachineMaterialAnalyzer.IngredientGroup> groups = MachineMaterialAnalyzer.analyze(machine);
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        long cycle = System.currentTimeMillis() / 1000L;
        for (int i = 0; i < MAT_COLS * MAT_ROWS; i++) {
            int index = materialScroll * MAT_COLS + i;
            if (index >= groups.size()) {
                break;
            }
            MachineMaterialAnalyzer.IngredientGroup group = groups.get(index);
            int x = MAT_X + (i % MAT_COLS) * 18;
            int y = MAT_Y + (i / MAT_COLS) * 18;
            // 多选一原料组：轮显候选（与 JEI 一致）
            List<ItemStack> candidates = group.getCandidates();
            ItemStack one = candidates.get((int) (cycle % candidates.size())).copy();
            one.setCount(1);
            itemRender.renderItemAndEffectIntoGUI(mc.player, one, x, y);
            itemRender.renderItemOverlayIntoGUI(fontRenderer, one, x, y, formatCount(group.getCount()));
        }
        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        // 缓存槽真实数量 overlay（显示堆数量受 byte 钳制，仅超过 64 时绘制）
        GlStateManager.pushMatrix();
        GlStateManager.translate(guiLeft, guiTop, 0);
        GlStateManager.disableLighting();
        for (int i = 0; i < ContainerVirtualAssembler.BUFFER_VISIBLE; i++) {
            int trueCount = container.getBufferView().getTrueCount(i);
            ItemStack cached = container.getBufferView().getStackInSlot(i);
            int maxStack = cached.isEmpty() ? 64 : Math.max(1, cached.getMaxStackSize());
            if (trueCount <= maxStack) {
                continue;
            }
            int x = BUF_X + (i % BUF_COLS) * 18;
            int y = BUF_Y + (i / BUF_COLS) * 18;
            String text = formatCount(trueCount);
            float w = fontRenderer.getStringWidth(text) * 0.5f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x + 17 - w, y + 12, 300);
            GlStateManager.scale(0.5f, 0.5f, 1.0f);
            fontRenderer.drawStringWithShadow(text, 0, 0, 0xFFFFFF);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
        renderHoveredToolTip(mouseX, mouseY);
    }

    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        super.renderHoveredToolTip(mouseX, mouseY);
        // 材料网格 tooltip
        DynamicMachine machine = selectedMachine();
        if (machine == null) {
            return;
        }
        int relX = mouseX - guiLeft, relY = mouseY - guiTop;
        if (relX < MAT_X || relX >= MAT_X + MAT_COLS * 18 || relY < MAT_Y || relY >= MAT_Y + MAT_ROWS * 18) {
            return;
        }
        int index = materialScroll * MAT_COLS + ((relY - MAT_Y) / 18) * MAT_COLS + (relX - MAT_X) / 18;
        List<MachineMaterialAnalyzer.IngredientGroup> groups = MachineMaterialAnalyzer.analyze(machine);
        if (index < 0 || index >= groups.size()) {
            return;
        }
        MachineMaterialAnalyzer.IngredientGroup group = groups.get(index);
        List<String> tooltip = new ArrayList<>();
        ItemStack first = group.getCandidates().get(0);
        tooltip.add(first.getDisplayName() + " x" + group.getCount());
        if (group.getCandidates().size() > 1) {
            tooltip.add(I18n.format("gui.virtualassembler.any_of"));
            long cycle = System.currentTimeMillis() / 1000L;
            int current = (int) (cycle % group.getCandidates().size());
            for (int i = 0; i < group.getCandidates().size(); i++) {
                String prefix = i == current ? " §a> §r" : "   ";
                tooltip.add(prefix + group.getCandidates().get(i).getDisplayName());
            }
        }
        drawHoveringText(tooltip, mouseX, mouseY);
    }

    // ==================== 交互 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        searchField.mouseClicked(mouseX, mouseY, mouseButton);
        int relX = mouseX - guiLeft, relY = mouseY - guiTop;
        if (relX >= LIST_X && relX < LIST_X + LIST_W && relY >= LIST_Y && relY < LIST_Y + LIST_ROWS * ROW_H) {
            int index = machineScroll + (relY - LIST_Y) / ROW_H;
            if (index >= 0 && index < filteredMachines.size()) {
                selectMachine(filteredMachines.get(index));
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            // shift = 装配最大份数
            PacketHandler.INSTANCE.sendToServer(
                    new PktVirtualAssemble(container.getOwner().getPos(), isShiftKeyDown()));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (searchField.isFocused() && searchField.textboxKeyTyped(typedChar, keyCode)) {
            applyFilter();
            return;
        }
        super.keyTyped(typedChar, keyCode);
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
        int delta = wheel > 0 ? -1 : 1;

        // 机器列表区域
        if (mouseX >= LIST_X && mouseX < LIST_X + LIST_W && mouseY >= LIST_Y && mouseY < LIST_Y + LIST_ROWS * ROW_H) {
            int maxScroll = Math.max(0, filteredMachines.size() - LIST_ROWS);
            machineScroll = Math.max(0, Math.min(maxScroll, machineScroll + delta));
            return;
        }
        // 材料网格区域（按行翻页）
        if (mouseX >= MAT_X && mouseX < MAT_X + MAT_COLS * 18 && mouseY >= MAT_Y && mouseY < MAT_Y + MAT_ROWS * 18) {
            DynamicMachine machine = selectedMachine();
            if (machine != null) {
                int rows = (MachineMaterialAnalyzer.analyze(machine).size() + MAT_COLS - 1) / MAT_COLS;
                int maxScroll = Math.max(0, rows - MAT_ROWS);
                materialScroll = Math.max(0, Math.min(maxScroll, materialScroll + delta));
            }
            return;
        }
        // 缓存槽区域：滚轮翻页（按行，9 的倍数），本地即时生效 + 发包同步服务端
        if (mouseX >= BUF_X && mouseX < BUF_X + BUF_COLS * 18 && mouseY >= BUF_Y && mouseY < BUF_Y + BUF_ROWS * 18) {
            int offset = container.getBufferScrollOffset() + delta * 9;
            offset = Math.max(0, Math.min(container.getMaxBufferScrollOffset(), offset));
            container.setBufferScrollOffset(offset);
            PacketHandler.INSTANCE.sendToServer(
                    new PktVirtualAssemblerScroll(container.getOwner().getPos(), offset));
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        searchField.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String formatCount(int count) {
        if (count >= 1000000) {
            return (count / 1000000) + "M";
        }
        if (count >= 10000) {
            return (count / 1000) + "k";
        }
        return String.valueOf(count);
    }
}

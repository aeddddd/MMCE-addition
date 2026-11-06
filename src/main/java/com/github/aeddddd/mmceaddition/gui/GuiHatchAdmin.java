package com.github.aeddddd.mmceaddition.gui;

import com.github.aeddddd.mmceaddition.client.HatchAdminClientData;
import com.github.aeddddd.mmceaddition.client.HatchHighlightHandler;
import com.github.aeddddd.mmceaddition.manager.HatchAdminManager;
import com.github.aeddddd.mmceaddition.manager.HatchType;
import com.github.aeddddd.mmceaddition.network.PacketHandler;
import com.github.aeddddd.mmceaddition.network.PktHatchAdminAction;
import com.github.aeddddd.mmceaddition.network.PktHatchAdminRequest;
import com.github.aeddddd.mmceaddition.network.PktHatchAdminSync;
import com.github.aeddddd.mmceaddition.network.PktHatchProfilerToggle;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.DimensionType;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓室管理工具 GUI（纯客户端界面，数据经网络包由服务端权威提供）。
 * <p>
 * 视觉风格对齐 MMCE：窗口框架、深色面板与按钮全部取样自
 * {@code modularmachinery:textures/gui/guicontroller_large.png}，
 * 以 1px 纯色区域 9-patch 拉伸拼接；曲线图的柱条同样取纹理白色像素
 * 经 GL 着色绘制，不做程序化绘制。
 * <p>
 * 布局（320x240）：
 * <ul>
 *   <li>y6 标题；y16 页签（仓室列表/性能分析）+ 刷新按钮</li>
 *   <li>列表页：y36 筛选行（类型/网络/排序），y54..188 深色面板列表
 *       （7 行双行行高 18：名称行 + [维度] 坐标/状态行，滚轮翻页），
 *       y194 批量操作行（IO 全开/全关、清空缓存、高亮），y214 总数/在线统计行</li>
 *   <li>性能页：y36 采样开关，y56..96 统计行（服务器 MSPT/TPS、上周期/累计注入、
 *       注册与积压、在线离线），y108 三条变化曲线（服务器 tick / 注入周期 / 仓室总耗时），
 *       y170 耗时 Top 面板</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public class GuiHatchAdmin extends GuiScreen {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("modularmachinery", "textures/gui/guicontroller_large.png");

    private static final int W = 320, H = 240;

    /** 框架取样区（guicontroller_large.png）：四角/四边/填充。 */
    private static final int FRAME = 4;
    /** 深色面板取样区：顶暗边 #373737、填充 #151515、右/底亮边 #FFFFFF。 */
    private static final int PANEL_TOP_U = 8, PANEL_TOP_V = 7;
    private static final int PANEL_FILL_U = 50, PANEL_FILL_V = 60;
    private static final int PANEL_RIGHT_U = 143, PANEL_RIGHT_V = 60;
    private static final int PANEL_BOTTOM_U = 50, PANEL_BOTTOM_V = 123;
    /** 曲线柱条取样区：白色像素（GL 着色后即为目标色）。 */
    private static final int WHITE_U = 143, WHITE_V = 60;
    /** 按钮取样区（MMCE 控制器 GUI 顶部按钮去图标后的纯色部分）。 */
    private static final int BTN_TL_U = 150, BTN_TL_V = 7;
    private static final int BTN_TOP_U = 151, BTN_TOP_V = 7;
    private static final int BTN_LEFT_U = 150, BTN_LEFT_V = 8;
    private static final int BTN_FILL_U = 152, BTN_FILL_V = 8;
    private static final int BTN_RIGHT_U = 167, BTN_RIGHT_V = 8;
    private static final int BTN_BOTTOM_U = 151, BTN_BOTTOM_V = 24;

    private static final int COLOR_TITLE = 0x404040;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_INFO = 0x9A9A9A;

    // ==================== 列表页布局 ====================
    private static final int LIST_PANEL_X = 7, LIST_PANEL_Y = 54, LIST_PANEL_W = 306, LIST_PANEL_H = 134;
    private static final int PERF_TOP_PANEL_Y = 170, PERF_TOP_PANEL_H = 58;
    private static final int CHART_PANEL_Y = 108, CHART_PANEL_H = 56;
    private static final int ROW_H = 18;
    private static final int LIST_VISIBLE = 7;
    private static final int PERF_VISIBLE = 3;

    // ==================== 按钮 ID ====================
    private static final int BTN_TAB_LIST = 0;
    private static final int BTN_TAB_PERF = 1;
    private static final int BTN_REFRESH = 2;
    private static final int BTN_FILTER_TYPE = 3;
    private static final int BTN_FILTER_NET = 4;
    private static final int BTN_FILTER_SORT = 5;
    private static final int BTN_IO_ON = 6;
    private static final int BTN_IO_OFF = 7;
    private static final int BTN_CLEAR = 8;
    private static final int BTN_HIGHLIGHT = 9;
    private static final int BTN_PROFILER = 10;

    /** 类型筛选预设：掩码 + 语言键。 */
    private static final int[] TYPE_MASKS;
    private static final String[] TYPE_KEYS = {
            "gui.hatchadmin.filter.all", "gui.hatchadmin.filter.item", "gui.hatchadmin.filter.fluid",
            "gui.hatchadmin.filter.energy", "gui.hatchadmin.filter.ae",
            "gui.hatchadmin.filter.controller", "gui.hatchadmin.filter.addon"
    };

    static {
        int item = HatchType.ITEM_IN.bit() | HatchType.ITEM_OUT.bit()
                | HatchType.ME_ITEM_IN.bit() | HatchType.ME_ITEM_OUT.bit() | HatchType.ASYNC_ITEM_OUT.bit();
        int fluid = HatchType.FLUID_IN.bit() | HatchType.FLUID_OUT.bit()
                | HatchType.ME_FLUID_IN.bit() | HatchType.ME_FLUID_OUT.bit() | HatchType.ASYNC_FLUID_OUT.bit();
        int energy = HatchType.ENERGY_IN.bit() | HatchType.ENERGY_OUT.bit();
        int ae = HatchType.ME_ITEM_IN.bit() | HatchType.ME_ITEM_OUT.bit()
                | HatchType.ME_FLUID_IN.bit() | HatchType.ME_FLUID_OUT.bit()
                | HatchType.ME_PATTERN_PROVIDER.bit() | HatchType.ASYNC_ITEM_OUT.bit()
                | HatchType.ASYNC_FLUID_OUT.bit() | HatchType.PATTERN_ASSEMBLY.bit()
                | HatchType.OUTPUT_ASSEMBLY.bit();
        int addon = HatchType.ASYNC_ITEM_OUT.bit() | HatchType.ASYNC_FLUID_OUT.bit()
                | HatchType.PATTERN_ASSEMBLY.bit() | HatchType.OUTPUT_ASSEMBLY.bit()
                | HatchType.INPUT_ASSEMBLY.bit();
        TYPE_MASKS = new int[]{HatchType.ALL_MASK, item, fluid, energy, ae,
                HatchType.CONTROLLER.bit(), addon};
    }

    private static final String[] NET_KEYS = {
            "gui.hatchadmin.filter.all", "gui.hatchadmin.filter.online", "gui.hatchadmin.filter.offline"
    };
    private static final String[] SORT_KEYS = {
            "gui.hatchadmin.sort.type", "gui.hatchadmin.sort.buffered", "gui.hatchadmin.sort.tick"
    };

    /** 当前页签：0 列表，1 性能。 */
    private int tab = 0;
    private int typePreset;
    private int netFilter;
    private int sortBy;
    /** 列表滚动（行偏移）。 */
    private int scrollRow;
    /** 选中行（entries 列表下标，-1 无选中）。 */
    private int selectedIndex = -1;
    /** 是否已发起过首次扫描请求（避免 resize 重复请求）。 */
    private boolean requested;

    /** 行首图标缓存："icon@meta" → ItemStack。 */
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    private int guiLeft, guiTop;

    public GuiHatchAdmin() {
    }

    // ==================== 初始化与按钮 ====================

    @Override
    public void initGui() {
        guiLeft = (width - W) / 2;
        guiTop = (height - H) / 2;
        rebuildButtons();
        if (!requested) {
            requested = true;
            sendRequest();
        }
    }

    private void rebuildButtons() {
        buttonList.clear();
        int y = guiTop + 16;
        buttonList.add(new MMButton(BTN_TAB_LIST, guiLeft + 10, y, 76, 16,
                I18n.format("gui.hatchadmin.tab.list")));
        buttonList.add(new MMButton(BTN_TAB_PERF, guiLeft + 90, y, 76, 16,
                I18n.format("gui.hatchadmin.tab.perf")));
        buttonList.add(new MMButton(BTN_REFRESH, guiLeft + W - 68, y, 58, 16,
                I18n.format("gui.hatchadmin.refresh")));
        if (tab == 0) {
            int fy = guiTop + 36;
            buttonList.add(new MMButton(BTN_FILTER_TYPE, guiLeft + 10, fy, 100, 13,
                    I18n.format("gui.hatchadmin.filter.type", I18n.format(TYPE_KEYS[typePreset]))));
            buttonList.add(new MMButton(BTN_FILTER_NET, guiLeft + 114, fy, 90, 13,
                    I18n.format("gui.hatchadmin.filter.net", I18n.format(NET_KEYS[netFilter]))));
            buttonList.add(new MMButton(BTN_FILTER_SORT, guiLeft + 208, fy, 90, 13,
                    I18n.format("gui.hatchadmin.filter.sort", I18n.format(SORT_KEYS[sortBy]))));
            int ay = guiTop + 194;
            buttonList.add(new MMButton(BTN_IO_ON, guiLeft + 10, ay, 60,
                    I18n.format("gui.hatchadmin.io_on")));
            buttonList.add(new MMButton(BTN_IO_OFF, guiLeft + 74, ay, 60,
                    I18n.format("gui.hatchadmin.io_off")));
            buttonList.add(new MMButton(BTN_CLEAR, guiLeft + 138, ay, 68,
                    I18n.format("gui.hatchadmin.clear")));
            buttonList.add(new MMButton(BTN_HIGHLIGHT, guiLeft + 210, ay, 56,
                    I18n.format("gui.hatchadmin.highlight")));
        } else {
            buttonList.add(new MMButton(BTN_PROFILER, guiLeft + 10, guiTop + 36, 96, 13,
                    I18n.format(HatchAdminClientData.profilerEnabled
                            ? "gui.hatchadmin.profiler_stop" : "gui.hatchadmin.profiler_start")));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BTN_TAB_LIST:
                tab = 0;
                scrollRow = 0;
                rebuildButtons();
                sendRequest();
                break;
            case BTN_TAB_PERF:
                tab = 1;
                scrollRow = 0;
                rebuildButtons();
                PacketHandler.INSTANCE.sendToServer(new PktHatchAdminRequest(
                        HatchType.ALL_MASK, HatchAdminManager.NET_ALL, HatchAdminManager.SORT_TICK_TIME));
                break;
            case BTN_REFRESH:
                sendRequest();
                break;
            case BTN_FILTER_TYPE:
                typePreset = (typePreset + 1) % TYPE_MASKS.length;
                scrollRow = 0;
                selectedIndex = -1;
                rebuildButtons();
                sendRequest();
                break;
            case BTN_FILTER_NET:
                netFilter = (netFilter + 1) % 3;
                scrollRow = 0;
                selectedIndex = -1;
                rebuildButtons();
                sendRequest();
                break;
            case BTN_FILTER_SORT:
                sortBy = (sortBy + 1) % 3;
                scrollRow = 0;
                rebuildButtons();
                sendRequest();
                break;
            case BTN_IO_ON:
                PacketHandler.INSTANCE.sendToServer(
                        new PktHatchAdminAction(PktHatchAdminAction.ACTION_IO_ON, TYPE_MASKS[typePreset]));
                break;
            case BTN_IO_OFF:
                PacketHandler.INSTANCE.sendToServer(
                        new PktHatchAdminAction(PktHatchAdminAction.ACTION_IO_OFF, TYPE_MASKS[typePreset]));
                break;
            case BTN_CLEAR:
                PacketHandler.INSTANCE.sendToServer(
                        new PktHatchAdminAction(PktHatchAdminAction.ACTION_CLEAR_BUFFERS, TYPE_MASKS[typePreset]));
                break;
            case BTN_HIGHLIGHT:
                highlightSelection();
                break;
            case BTN_PROFILER:
                PacketHandler.INSTANCE.sendToServer(
                        new PktHatchProfilerToggle(!HatchAdminClientData.profilerEnabled));
                break;
            default:
                break;
        }
    }

    private void sendRequest() {
        PacketHandler.INSTANCE.sendToServer(
                new PktHatchAdminRequest(TYPE_MASKS[typePreset], netFilter, sortBy));
    }

    /** 高亮：选中条目；按住 Shift 时高亮当前列表全部条目（仅同维度）。 */
    private void highlightSelection() {
        if (mc.player == null) {
            return;
        }
        List<PktHatchAdminSync.EntryData> entries = HatchAdminClientData.entries;
        if (isShiftKeyDown()) {
            int added = 0;
            for (PktHatchAdminSync.EntryData e : entries) {
                if (e.dim == mc.player.dimension && added < 256) {
                    HatchHighlightHandler.add(e.pos);
                    added++;
                }
            }
            if (added == 0) {
                mc.player.sendStatusMessage(new TextComponentTranslation("gui.hatchadmin.no_selection"), true);
            }
            return;
        }
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            mc.player.sendStatusMessage(new TextComponentTranslation("gui.hatchadmin.no_selection"), true);
            return;
        }
        PktHatchAdminSync.EntryData e = entries.get(selectedIndex);
        if (e.dim != mc.player.dimension) {
            mc.player.sendStatusMessage(new TextComponentTranslation("gui.hatchadmin.dim_mismatch", e.dim), true);
            return;
        }
        HatchHighlightHandler.add(e.pos);
    }

    // ==================== 绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        drawWindow();
        fontRenderer.drawString(I18n.format("gui.hatchadmin.title"),
                guiLeft + 10, guiTop + 6, COLOR_TITLE);

        if (tab == 0) {
            drawListTab(mouseX, mouseY);
        } else {
            drawPerfTab(mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 窗口框架：MMCE 控制器 GUI 的四角/四边/填充 9-patch。 */
    private void drawWindow() {
        mc.getTextureManager().bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, FRAME, FRAME);
        drawTexturedModalRect(guiLeft + W - FRAME, guiTop, 172, 0, FRAME, FRAME);
        drawTexturedModalRect(guiLeft, guiTop + H - FRAME, 0, 209, FRAME, FRAME);
        drawTexturedModalRect(guiLeft + W - FRAME, guiTop + H - FRAME, 172, 209, FRAME, FRAME);
        stretch(guiLeft + FRAME, guiTop, 4, 0, W - FRAME * 2, FRAME);
        stretch(guiLeft + FRAME, guiTop + H - FRAME, 4, 209, W - FRAME * 2, FRAME);
        stretch(guiLeft, guiTop + FRAME, 0, 4, FRAME, H - FRAME * 2);
        stretch(guiLeft + W - FRAME, guiTop + FRAME, 172, 4, FRAME, H - FRAME * 2);
        stretch(guiLeft + FRAME, guiTop + FRAME, 4, 4, W - FRAME * 2, H - FRAME * 2);
    }

    /** 深色面板（列表/曲线/Top 区域底色）。 */
    private void drawPanel(int x, int y, int w, int h) {
        mc.getTextureManager().bindTexture(TEXTURE);
        stretch(x, y, PANEL_TOP_U, PANEL_TOP_V, w, 1);
        stretch(x, y, PANEL_TOP_U, PANEL_TOP_V, 1, h);
        stretch(x + w - 1, y, PANEL_RIGHT_U, PANEL_RIGHT_V, 1, h);
        stretch(x, y + h - 1, PANEL_BOTTOM_U, PANEL_BOTTOM_V, w, 1);
        stretch(x + 1, y + 1, PANEL_FILL_U, PANEL_FILL_V, w - 2, h - 2);
    }

    /** 把纹理中的 1x1 纯色区域拉伸到目标矩形。 */
    private void stretch(int x, int y, int u, int v, int w, int h) {
        drawScaledCustomSizeModalRect(x, y, u, v, 1, 1, w, h, 256, 256);
    }

    // ==================== 列表页 ====================

    private void drawListTab(int mouseX, int mouseY) {
        drawPanel(guiLeft + LIST_PANEL_X, guiTop + LIST_PANEL_Y, LIST_PANEL_W, LIST_PANEL_H);
        List<PktHatchAdminSync.EntryData> entries = HatchAdminClientData.entries;
        if (entries.isEmpty()) {
            String empty = I18n.format("gui.hatchadmin.empty");
            fontRenderer.drawString(empty,
                    guiLeft + LIST_PANEL_X + (LIST_PANEL_W - fontRenderer.getStringWidth(empty)) / 2,
                    guiTop + LIST_PANEL_Y + LIST_PANEL_H / 2 - 4, COLOR_INFO);
        } else {
            int max = Math.min(LIST_VISIBLE, entries.size() - scrollRow);
            for (int i = 0; i < max; i++) {
                drawRow(entries.get(scrollRow + i), scrollRow + i,
                        guiLeft + LIST_PANEL_X + 1, guiTop + LIST_PANEL_Y + 2 + i * ROW_H,
                        LIST_PANEL_W - 2);
            }
        }
        // 总数 + 在线/离线统计行
        String truncated = HatchAdminClientData.totalFound > entries.size()
                ? I18n.format("gui.hatchadmin.truncated", entries.size()) : "";
        fontRenderer.drawString(I18n.format("gui.hatchadmin.total", HatchAdminClientData.totalFound, truncated)
                        + I18n.format("gui.hatchadmin.net_counts",
                        HatchAdminClientData.onlineCount, HatchAdminClientData.offlineCount),
                guiLeft + 10, guiTop + 214, COLOR_TITLE);
    }

    /** 单行：选中底色 + 图标 + 名称行 + [维度] 坐标/状态行。 */
    private void drawRow(PktHatchAdminSync.EntryData e, int index, int x, int y, int w) {
        mc.getTextureManager().bindTexture(TEXTURE);
        if (index >= 0 && index == selectedIndex) {
            stretch(x, y, PANEL_TOP_U, PANEL_TOP_V, w, ROW_H);
        }
        ItemStack icon = iconFor(e);
        if (!icon.isEmpty()) {
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableDepth();
            itemRender.renderItemAndEffectIntoGUI(mc.player, icon, x + 2, y + 1);
            GlStateManager.disableDepth();
            RenderHelper.disableStandardItemLighting();
        }
        fontRenderer.drawString(fontRenderer.trimStringToWidth(e.name, w - 24),
                x + 22, y + 1, COLOR_TEXT);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(infoLine(e), w - 24),
                x + 22, y + 9, COLOR_INFO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 状态行：[维度] 坐标 | 类型 | IO | 网络 | 缓存 | 耗时。 */
    private String infoLine(PktHatchAdminSync.EntryData e) {
        StringBuilder sb = new StringBuilder();
        sb.append("§8[§b").append(dimName(e.dim)).append("§8] §7")
                .append(e.pos.getX()).append(',').append(e.pos.getY()).append(',').append(e.pos.getZ());
        // 类型序号来自网络包，畸形/版本不符时可能越界：越界就跳过该段，不让客户端崩溃
        HatchType[] types = HatchType.values();
        if (e.typeOrdinal >= 0 && e.typeOrdinal < types.length) {
            sb.append(" §8| §7").append(I18n.format(types[e.typeOrdinal].langKey()));
        }
        if (e.autoIO >= 0) {
            sb.append(" §8| ").append(e.autoIO == 1
                    ? "§a" + I18n.format("gui.hatchadmin.status.io_on")
                    : "§c" + I18n.format("gui.hatchadmin.status.io_off"));
        }
        if (e.network >= 0) {
            sb.append(" §8| ").append(e.network == 1
                    ? "§a" + I18n.format("gui.hatchadmin.status.online")
                    : "§c" + I18n.format("gui.hatchadmin.status.offline"));
        }
        if (e.buffered >= 0) {
            sb.append(" §8| §e").append(formatCount(e.buffered));
        }
        if (e.avgNanos > 0) {
            sb.append(" §8| §b").append(formatNanos(e.avgNanos)).append("/t");
        }
        return sb.toString();
    }

    // ==================== 性能页 ====================

    private void drawPerfTab(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("gui.hatchadmin.profiler_hint"),
                guiLeft + 112, guiTop + 39, COLOR_TITLE);

        // ---- 统计行 ----
        int x = guiLeft + 10;
        int y = guiTop + 56;
        double mspt = HatchAdminClientData.serverMspt;
        String msptText = (mspt > 50 ? "§c" : "§a") + String.format("%.2f", mspt);
        fontRenderer.drawString(I18n.format("gui.hatchadmin.perf.server",
                msptText, String.format("%.1f", HatchAdminClientData.serverTps)), x, y, COLOR_TITLE);
        int[] opsHistory = HatchAdminClientData.mgrOpsHistory;
        int lastOps = opsHistory.length == 0 ? 0 : opsHistory[opsHistory.length - 1];
        fontRenderer.drawString(I18n.format("gui.hatchadmin.perf.cycle",
                HatchAdminClientData.mgrCycleMicros, lastOps,
                HatchAdminClientData.mgrInjectedItems, HatchAdminClientData.mgrInjectedFluids),
                x, y + 10, COLOR_TITLE);
        fontRenderer.drawString(I18n.format("gui.hatchadmin.perf.total",
                HatchAdminClientData.mgrTotalInjectedItems, HatchAdminClientData.mgrTotalInjectedFluids,
                HatchAdminClientData.mgrTotalCycles, HatchAdminClientData.mgrTotalOps),
                x, y + 20, COLOR_TITLE);
        int[] reg = HatchAdminClientData.mgrRegistered;
        int[] dirty = HatchAdminClientData.mgrDirty;
        fontRenderer.drawString(I18n.format("gui.hatchadmin.perf.registered",
                reg[0], reg[1], reg[2], reg[3], dirty[0], dirty[1], dirty[2], dirty[3]),
                x, y + 30, COLOR_TITLE);
        fontRenderer.drawString(I18n.format("gui.hatchadmin.perf.network",
                HatchAdminClientData.onlineCount, HatchAdminClientData.offlineCount),
                x, y + 40, COLOR_TITLE);

        // ---- 变化曲线 ----
        int px = guiLeft + 7, py = guiTop + CHART_PANEL_Y, pw = 306;
        drawPanel(px, py, pw, CHART_PANEL_H);
        int chartW = pw - 4;
        drawChart(px + 2, py + 2, chartW, 15, toDoubles(HatchAdminClientData.serverTickHistory, 1000.0),
                0.35F, 0.9F, 0.35F,
                I18n.format("gui.hatchadmin.chart.server"), "ms");
        drawChart(px + 2, py + 19, chartW, 15, toDoubles(HatchAdminClientData.mgrCycleHistory, 1.0),
                1.0F, 0.8F, 0.2F,
                I18n.format("gui.hatchadmin.chart.cycle"), "µs");
        long[] hatchHistory = HatchAdminClientData.profilerNanosHistory;
        if (hatchHistory.length == 0) {
            String closed = I18n.format("gui.hatchadmin.chart.hatch")
                    + " §8- " + I18n.format("gui.hatchadmin.chart.closed");
            fontRenderer.drawString(closed, px + 4, py + 39, COLOR_INFO);
        } else {
            drawChart(px + 2, py + 36, chartW, 15, toDoubles(hatchHistory, 1000.0),
                    0.9F, 0.45F, 0.9F,
                    I18n.format("gui.hatchadmin.chart.hatch"), "µs/s");
        }

        // ---- 耗时 Top 面板（客户端按平均耗时降序） ----
        int ty = guiTop + PERF_TOP_PANEL_Y;
        drawPanel(guiLeft + 7, ty, 306, PERF_TOP_PANEL_H);
        List<PktHatchAdminSync.EntryData> top = new ArrayList<>(HatchAdminClientData.entries);
        top.sort((a, b) -> Long.compare(b.avgNanos, a.avgNanos));
        if (!HatchAdminClientData.profilerEnabled) {
            String hint = I18n.format("gui.hatchadmin.profiler_hint");
            fontRenderer.drawString(fontRenderer.trimStringToWidth(hint, 298),
                    guiLeft + 11, ty + PERF_TOP_PANEL_H / 2 - 4, COLOR_INFO);
        } else {
            int max = Math.min(PERF_VISIBLE, top.size() - scrollRow);
            for (int i = 0; i < max; i++) {
                drawRow(top.get(scrollRow + i), -1,
                        guiLeft + 8, ty + 2 + i * ROW_H, 304);
            }
        }
    }

    /**
     * 单条变化曲线：柱条取纹理白色像素经 GL 着色绘制，左上角叠加标题与峰值。
     *
     * @param data  归一化前的样本（已换算到展示单位）
     * @param r/g/b 柱条颜色
     * @param unit  峰值单位（ms / µs / µs/s）
     */
    private void drawChart(int x, int y, int w, int h, double[] data, float r, float g, float b,
                           String label, String unit) {
        double max = 0;
        for (double v : data) {
            max = Math.max(max, v);
        }
        mc.getTextureManager().bindTexture(TEXTURE);
        if (max > 0) {
            int barW = Math.max(1, w / data.length);
            int innerH = h - 2;
            GlStateManager.color(r, g, b, 1.0F);
            for (int i = 0; i < data.length; i++) {
                int barH = (int) Math.round(data[i] * innerH / max);
                if (barH > 0) {
                    stretch(x + i * barW, y + h - 1 - barH, WHITE_U, WHITE_V, Math.min(barW, w - i * barW), barH);
                }
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        fontRenderer.drawString(label + " §8" + I18n.format("gui.hatchadmin.chart.peak",
                formatPlain(max) + unit), x + 2, y + 1, COLOR_INFO);
    }

    private static double[] toDoubles(int[] data, double scale) {
        double[] out = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i] / scale;
        }
        return out;
    }

    private static double[] toDoubles(long[] data, double scale) {
        double[] out = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i] / scale;
        }
        return out;
    }

    // ==================== 交互 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (tab == 0) {
            int row = rowAt(mouseX, mouseY, LIST_PANEL_X, LIST_PANEL_Y, LIST_PANEL_W, LIST_PANEL_H);
            if (row >= 0 && scrollRow + row < HatchAdminClientData.entries.size()) {
                selectedIndex = scrollRow + row;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int relX = Mouse.getEventX() * width / mc.displayWidth - guiLeft;
        int relY = height - Mouse.getEventY() * height / mc.displayHeight - 1 - guiTop;
        int panelY = tab == 0 ? LIST_PANEL_Y : PERF_TOP_PANEL_Y;
        int panelH = tab == 0 ? LIST_PANEL_H : PERF_TOP_PANEL_H;
        if (relX < 7 || relX >= 7 + 306 || relY < panelY || relY >= panelY + panelH) {
            return;
        }
        int visible = tab == 0 ? LIST_VISIBLE : PERF_VISIBLE;
        int maxScroll = Math.max(0, HatchAdminClientData.entries.size() - visible);
        int next = scrollRow + (wheel > 0 ? -1 : 1);
        scrollRow = Math.max(0, Math.min(next, maxScroll));
    }

    /** 命中检测：返回面板内行号（0 起），未命中返回 -1。 */
    private int rowAt(int mouseX, int mouseY, int panelX, int panelY, int panelW, int panelH) {
        int relX = mouseX - guiLeft - panelX;
        int relY = mouseY - guiTop - panelY;
        if (relX < 0 || relX >= panelW || relY < 2 || relY >= panelH - 2) {
            return -1;
        }
        return (relY - 2) / ROW_H;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== 格式化工具 ====================

    private ItemStack iconFor(PktHatchAdminSync.EntryData e) {
        String key = e.icon + '@' + e.meta;
        ItemStack cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }
        ItemStack stack = ItemStack.EMPTY;
        Item item = Item.getByNameOrId(e.icon);
        if (item != null) {
            stack = new ItemStack(item, 1, e.meta);
        }
        iconCache.put(key, stack);
        return stack;
    }

    /** 维度显示名：常见维度走语言键，其余取 DimensionType 注册名。 */
    private static String dimName(int dim) {
        switch (dim) {
            case 0:
                return I18n.format("gui.hatchadmin.dim.overworld");
            case -1:
                return I18n.format("gui.hatchadmin.dim.nether");
            case 1:
                return I18n.format("gui.hatchadmin.dim.end");
            default:
                try {
                    return DimensionType.getById(dim).getName();
                } catch (Exception e) {
                    return "DIM " + dim;
                }
        }
    }

    /** 数量缩写（对齐 GuiBufferView 的角标风格）。 */
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

    /** 纳秒耗时的人性化格式：亚微秒保留两位小数，毫秒级换单位。 */
    private static String formatNanos(long nanos) {
        if (nanos < 1000) {
            return String.format("%.2fµs", nanos / 1000.0);
        }
        if (nanos < 1_000_000) {
            return String.format("%.1fµs", nanos / 1000.0);
        }
        return String.format("%.2fms", nanos / 1_000_000.0);
    }

    /** 曲线峰值格式：整数去小数点，小数保留一位。 */
    private static String formatPlain(double value) {
        if (value >= 100) {
            return String.valueOf(Math.round(value));
        }
        return String.format("%.1f", value);
    }

    /**
     * MMCE 风格按钮：取样控制器 GUI 顶部按钮的纯色部分（去图标），
     * 顶/左暗边 + 右/下亮边 + #8B8B8B 填充。
     */
    @SideOnly(Side.CLIENT)
    private static class MMButton extends GuiButton {

        MMButton(int id, int x, int y, int w, String text) {
            this(id, x, y, w, 14, text);
        }

        MMButton(int id, int x, int y, int w, int h, String text) {
            super(id, x, y, w, h, text);
        }

        @Override
        public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!visible) {
                return;
            }
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            mc.getTextureManager().bindTexture(TEXTURE);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            drawTexturedModalRect(x, y, BTN_TL_U, BTN_TL_V, 1, 1);
            drawScaledCustomSizeModalRect(x + 1, y, BTN_TOP_U, BTN_TOP_V, 1, 1, width - 1, 1, 256, 256);
            drawScaledCustomSizeModalRect(x, y + 1, BTN_LEFT_U, BTN_LEFT_V, 1, 1, 1, height - 1, 256, 256);
            drawScaledCustomSizeModalRect(x + width - 1, y, BTN_RIGHT_U, BTN_RIGHT_V, 1, 1, 1, height, 256, 256);
            drawScaledCustomSizeModalRect(x + 1, y + height - 1, BTN_BOTTOM_U, BTN_BOTTOM_V, 1, 1,
                    width - 1, 1, 256, 256);
            drawScaledCustomSizeModalRect(x + 1, y + 1, BTN_FILL_U, BTN_FILL_V, 1, 1,
                    width - 2, height - 2, 256, 256);
            drawCenteredString(mc.fontRenderer, displayString,
                    x + width / 2, y + (height - 8) / 2, hovered ? 0xFFFFFF : 0xE0E0E0);
        }
    }
}

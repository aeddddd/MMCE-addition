package com.github.aeddddd.mmceaddition.tile;

import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.inventories.InternalInventory;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.networking.IGrid;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.helpers.patternprovider.PatternContainer;
import ae2.util.inv.AppEngInternalInventory;
import ae2.util.inv.InternalInventoryHost;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.compat.NetworkEnergyCompat;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import com.github.aeddddd.mmceaddition.tile.provider.AssemblyResourceComponents;
import com.github.aeddddd.mmceaddition.tile.slot.PatternAssemblySlot;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import com.github.aeddddd.mmceaddition.util.LongBufferFluidHandler;
import com.github.aeddddd.mmceaddition.util.LongBufferItemHandler;
import com.github.aeddddd.mmceaddition.util.LongFluidBuffer;
import com.github.aeddddd.mmceaddition.util.LongInputFluidHandler;
import com.github.aeddddd.mmceaddition.util.LongInputItemHandler;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import github.kasuminova.mmce.common.event.Phase;
import github.kasuminova.mmce.common.event.machine.MachineEvent;
import github.kasuminova.mmce.common.event.machine.MachineStructureUpdateEvent;
import github.kasuminova.mmce.common.event.recipe.RecipeCheckEvent;
import github.kasuminova.mmce.common.event.recipe.RecipeFailureEvent;
import github.kasuminova.mmce.common.event.recipe.RecipeFinishEvent;
import github.kasuminova.mmce.common.event.recipe.RecipeStartEvent;
import github.kasuminova.mmce.common.event.recipe.RecipeTickEvent;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementCatalyst;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementFluid;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementIngredientArray;
import hellfirepvp.modularmachinery.common.crafting.requirement.RequirementItem;
import github.kasuminova.mmce.common.itemtype.ChancedIngredientStack;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTile;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTileNotifiable;
import hellfirepvp.modularmachinery.common.util.IEnergyHandlerAsync;
import hellfirepvp.modularmachinery.common.util.IItemHandlerImpl;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * ME 样板总成 TileEntity。
 * <p>
 * 核心特性：
 * <ul>
 *   <li>36 个样板槽，每个槽独立输入/输出 Long 缓冲</li>
 *   <li>每个槽 12 个独立催化剂物品槽（中键点击样板槽显示）</li>
 *   <li>输出缓冲由 {@link MEAsyncOutputManager} 批量注入 AE</li>
 *   <li>通过 Mixin + ThreadLocal 实现检查期隔离</li>
 * </ul>
 */
public class TileMEPatternAssembly extends TileMENetworkComponent
        implements MachineComponentTile,
                   MachineComponentTileNotifiable,
                   ICraftingProvider,
                   InternalInventoryHost,
                   PatternContainer {

    public static final int PATTERNS = 36;
    public static final int SLOT_CATALYST_COUNT = PatternAssemblySlot.CATALYST_SLOTS;

    // 36 个样板槽（使用 AppEngInternalInventory 保持与原版一致）
    protected final AppEngInternalInventory patterns;

    // 36 个 PatternAssemblySlot
    protected final PatternAssemblySlot[] slots = new PatternAssemblySlot[PATTERNS];

    // 样板解析缓存：pattern slot index -> IPatternDetails
    protected final IPatternDetails[] details = new IPatternDetails[PATTERNS];

    // 自定义名称
    private String customName = "";

    // ThreadLocal：当前配方检查时应使用的 slot 索引（检查期隔离）
    private final ThreadLocal<Integer> activeSlotIndex = ThreadLocal.withInitial(() -> -1);

    // 聚合输入 handler（在检查期会根据 activeSlotIndex 动态切换实际访问的 slot）
    private final AggregatedInputItemHandler aggregatedInputItemHandler;
    private final AggregatedInputFluidHandler aggregatedInputFluidHandler;

    // 聚合输出 handler
    private final AggregatedOutputItemHandler aggregatedOutputItemHandler;
    private final AggregatedOutputFluidHandler aggregatedOutputFluidHandler;

    // 网络 RF 能源处理器：直接代理 AE 网络中存储的 RF（AE2Enhanced 能源通道）
    private final NetworkEnergyHandler energyHandler = new NetworkEnergyHandler();

    @Nonnull
    @Override
    public ItemStack getVisualItemStack() {
        return new ItemStack(RegistryHandler.ME_PATTERN_ASSEMBLY);
    }

    public TileMEPatternAssembly() {
        this.patterns = new AppEngInternalInventory(this, PATTERNS, 1) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return PatternDetailsHelper.isEncodedPattern(stack);
            }
        };
        for (int i = 0; i < PATTERNS; i++) {
            this.slots[i] = new PatternAssemblySlot(i, this);
        }
        this.aggregatedInputItemHandler = new AggregatedInputItemHandler();
        this.aggregatedInputFluidHandler = new AggregatedInputFluidHandler();
        this.aggregatedOutputItemHandler = new AggregatedOutputItemHandler();
        this.aggregatedOutputFluidHandler = new AggregatedOutputFluidHandler();
    }

    @Override
    protected void configureNode(IManagedGridNode node) {
        node.addService(ICraftingProvider.class, this);
    }

    @Override public IGrid getGrid() { return getMainNode().getGrid(); }
    @Override public InternalInventory getTerminalPatternInventory() { return patterns; }
    @Override public boolean containsPattern(AEItemKey pattern) {
        for (ItemStack stack : patterns) {
            if (pattern.equals(AEItemKey.of(stack))) return true;
        }
        return false;
    }
    @Override public long getTerminalSortOrder() { return pos.toLong(); }
    @Override public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(AEItemKey.of(getVisualItemStack()),
                new net.minecraft.util.text.TextComponentString(getCustomInventoryName()), Collections.emptyList());
    }
    @Override public void openTerminalPatternContainerGui(net.minecraft.entity.player.EntityPlayer player) {
        player.openGui(MMCEAddition.instance, com.github.aeddddd.mmceaddition.gui.GuiHandler.GUI_ME_PATTERN_ASSEMBLY,
                world, pos.getX(), pos.getY(), pos.getZ());
    }
    @Override public boolean canEditTerminalName() { return true; }
    @Override public void setTerminalCustomName(@Nullable String name) { setCustomName(name); }

    // ==================== MachineComponentTile ====================

    @Nullable
    @Override
    public MachineComponent<?> provideComponent() {
        // 返回一个可被 Mixin 识别的自定义输入总线组件。
        // 在 RecipeCraftingContext.updateComponents 阶段，Mixin 会把它展开为
        // 物品输入、物品输出、流体输入、流体输出四个独立组件，
        // 从而让同一个 Tile 同时充当输入仓与输出仓。
        return new PatternAssemblyItemInputBus(this);
    }

    public IItemHandlerModifiable getAggregatedInputItemHandler() {
        return aggregatedInputItemHandler;
    }

    public IFluidHandler getAggregatedInputFluidHandler() {
        return aggregatedInputFluidHandler;
    }

    public IItemHandlerModifiable getAggregatedOutputItemHandler() {
        return aggregatedOutputItemHandler;
    }

    public IFluidHandler getAggregatedOutputFluidHandler() {
        return aggregatedOutputFluidHandler;
    }

    public hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<IItemHandlerModifiable> createItemInputProcessingComponent() {
        return new hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<>(
                new PatternAssemblyItemInputBus(this), aggregatedInputItemHandler, null);
    }

    public hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<IItemHandlerModifiable> createItemOutputProcessingComponent() {
        return new hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<>(
                new PatternAssemblyItemOutputBus(this), aggregatedOutputItemHandler, null);
    }

    public hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<IFluidHandler> createFluidInputProcessingComponent() {
        return new hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<>(
                new PatternAssemblyFluidInputHatch(this), aggregatedInputFluidHandler, null);
    }

    public hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<IFluidHandler> createFluidOutputProcessingComponent() {
        return new hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<>(
                new PatternAssemblyFluidOutputHatch(this), aggregatedOutputFluidHandler, null);
    }

    /**
     * 能源输入组件：让样板总成同时充当能源输入仓，直接消耗网络 RF。
     */
    public hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<IEnergyHandlerAsync> createEnergyInputProcessingComponent() {
        return new hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<>(
                new PatternAssemblyEnergyHatch(this), energyHandler, null);
    }

    /**
     * @return 当前网络中存储的 RF 总量（供诊断命令使用）
     */
    public long getNetworkEnergy() {
        return NetworkEnergyCompat.getStoredEnergy(this);
    }

    /**
     * 可被 Mixin 识别的自定义物品输入总线组件。
     */
    public static class PatternAssemblyItemInputBus extends MachineComponent.ItemBus {
        private final TileMEPatternAssembly assembly;

        public PatternAssemblyItemInputBus(TileMEPatternAssembly assembly) {
            super(IOType.INPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IItemHandlerModifiable getContainerProvider() {
            return assembly.aggregatedInputItemHandler;
        }

        public TileMEPatternAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 自定义物品输出总线组件。
     */
    public static class PatternAssemblyItemOutputBus extends MachineComponent.ItemBus {
        private final TileMEPatternAssembly assembly;

        public PatternAssemblyItemOutputBus(TileMEPatternAssembly assembly) {
            super(IOType.OUTPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IItemHandlerModifiable getContainerProvider() {
            return assembly.aggregatedOutputItemHandler;
        }

        public TileMEPatternAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 自定义流体输入仓组件。
     */
    public static class PatternAssemblyFluidInputHatch extends MachineComponent.FluidHatch {
        private final TileMEPatternAssembly assembly;

        public PatternAssemblyFluidInputHatch(TileMEPatternAssembly assembly) {
            super(IOType.INPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IFluidHandler getContainerProvider() {
            return assembly.aggregatedInputFluidHandler;
        }

        public TileMEPatternAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 自定义流体输出仓组件。
     */
    public static class PatternAssemblyFluidOutputHatch extends MachineComponent.FluidHatch {
        private final TileMEPatternAssembly assembly;

        public PatternAssemblyFluidOutputHatch(TileMEPatternAssembly assembly) {
            super(IOType.OUTPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IFluidHandler getContainerProvider() {
            return assembly.aggregatedOutputFluidHandler;
        }

        public TileMEPatternAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 自定义能源输入仓组件。
     * <p>
     * 让 MMCE 的 RequirementEnergy 把样板总成识别为能源输入口，
     * 能量直接来自 AE 网络存储的 RF，不经过任何本地能源仓。
     */
    public static class PatternAssemblyEnergyHatch extends MachineComponent.EnergyHatch {
        private final TileMEPatternAssembly assembly;

        public PatternAssemblyEnergyHatch(TileMEPatternAssembly assembly) {
            super(IOType.INPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IEnergyHandlerAsync getContainerProvider() {
            return assembly.energyHandler;
        }

        public TileMEPatternAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 自定义能源输出仓组件。
     * <p>
     * 让 MMCE 的 RequirementEnergy(OUTPUT) 把样板总成识别为能源出口，
     * 配方产出的 RF 直接回注 AE 网络存储。
     */
    public static class PatternAssemblyEnergyOutputHatch extends MachineComponent.EnergyHatch {
        private final TileMEPatternAssembly assembly;

        public PatternAssemblyEnergyOutputHatch(TileMEPatternAssembly assembly) {
            super(IOType.OUTPUT);
            this.assembly = assembly;
        }

        @Nonnull
        @Override
        public IEnergyHandlerAsync getContainerProvider() {
            return assembly.energyHandler;
        }

        public TileMEPatternAssembly getAssembly() {
            return assembly;
        }
    }

    /**
     * 网络 RF 能源处理器。
     * <p>
     * 实现 MMCE 的 long 级异步能源接口，把"当前能量/提取能量"直接映射为
     * 对 AE 网络 RF 存量的查询与扣除（经 {@link NetworkEnergyCompat} 反射调用 AE2Enhanced API）：
     * <ul>
     *   <li>{@link #getCurrentEnergy()}：网络 RF 存量</li>
     *   <li>{@link #extractEnergy(long)}：全有或全无——先模拟确认存量充足再实际扣除，
     *       避免 RequirementEnergy 把部分提取误判为成功</li>
     *   <li>{@link #setCurrentEnergy(long)}：空实现——异步路径不会调用；网络能量不支持本地写回</li>
     *   <li>{@link #receiveEnergy(long)}：配方产 RF 时回注网络（全有或全无，先模拟再实际注入）</li>
     *   <li>未安装 AE2Enhanced 时表现为 0 能量，机器报缺能量，不会崩溃</li>
     * </ul>
     */
    private class NetworkEnergyHandler implements IEnergyHandlerAsync {

        @Override
        public long getCurrentEnergy() {
            return NetworkEnergyCompat.getStoredEnergy(TileMEPatternAssembly.this);
        }

        @Override
        public void setCurrentEnergy(long l) {
            // 异步路径不会调用；网络能量是共享存储，本地写回没有意义
        }

        @Override
        public long getMaxEnergy() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean extractEnergy(long amount) {
            if (amount <= 0) {
                return true;
            }
            // 全有或全无：先模拟，存量不足则一点不动
            if (NetworkEnergyCompat.extractEnergy(TileMEPatternAssembly.this, amount, true) < amount) {
                return false;
            }
            return NetworkEnergyCompat.extractEnergy(TileMEPatternAssembly.this, amount, false) >= amount;
        }

        @Override
        public boolean receiveEnergy(long amount) {
            // 全有或全无：先模拟确认网络可完整容纳本次产出，再实际回注
            if (NetworkEnergyCompat.insertEnergy(TileMEPatternAssembly.this, amount, true) >= amount) {
                return NetworkEnergyCompat.insertEnergy(TileMEPatternAssembly.this, amount, false) >= amount;
            }
            return false;
        }
    }

    // ==================== ICraftingProvider ====================

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        List<IPatternDetails> result = new ArrayList<>();
        for (IPatternDetails detail : details) if (detail != null) result.add(detail);
        return result;
    }

    @Override
    public boolean canMergePatternPush(IPatternDetails pattern) {
        return findPatternSlot(pattern) >= 0;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails pattern, int maxMultiplier) {
        return getMainNode().isActive() && findPatternSlot(pattern) >= 0
                ? Math.min(4096, Math.max(0, maxMultiplier)) : 0;
    }

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputHolder, int multiplier) {
        if (!getMainNode().isActive() || multiplier <= 0 || inputHolder == null) return false;
        int index = findPatternSlot(pattern);
        if (index < 0 || !pattern.supportsPushInputsToExternalInventory()) return false;
        Map<AEKey, Long> inputs = new LinkedHashMap<>();
        try {
            // AE2S already multiplied inputHolder. Do not multiply the quantities again.
            pattern.pushInputsToExternalInventory(inputHolder, (key, amount) -> {
                if (!(key instanceof AEItemKey) && !(key instanceof AEFluidKey)) {
                    throw new IllegalArgumentException("Unsupported pattern input: " + key);
                }
                if (amount <= 0) throw new IllegalArgumentException("Non-positive pattern input");
                // The existing MMCE fluid buffers store plain fluid types.
                if (key instanceof AEFluidKey fluid && fluid.toStack(1).tag != null) {
                    throw new IllegalArgumentException("Tagged fluid is not representable by this buffer");
                }
                inputs.merge(key, amount, Math::addExact);
            });
        } catch (ArithmeticException | IllegalArgumentException invalidInput) {
            return false;
        }
        PatternAssemblySlot slot = slots[index];
        synchronized (slot.getInputItemBuffer()) {
            synchronized (slot.getInputFluidBuffer()) {
                // Validate every input before accepting any materials. A rejected push
                // leaves both local buffers and the CPU's input holders unchanged.
                for (Map.Entry<AEKey, Long> input : inputs.entrySet()) {
                    long stored = input.getKey() instanceof AEItemKey item
                            ? slot.getInputItemBuffer().getAmount(new ItemVariant(item.toStack()))
                            : slot.getInputFluidBuffer().getAmount(((AEFluidKey) input.getKey()).getFluid());
                    if (input.getValue() > Long.MAX_VALUE - stored) return false;
                }
                for (Map.Entry<AEKey, Long> input : inputs.entrySet()) {
                    if (input.getKey() instanceof AEItemKey item) {
                        slot.getInputItemBuffer().insertLong(new ItemVariant(item.toStack()), input.getValue());
                    } else {
                        slot.getInputFluidBuffer().fillLong(((AEFluidKey) input.getKey()).getFluid(), input.getValue());
                    }
                }
                slot.getInputItemHandler().syncWithBuffer();
            }
        }
        markDirty();
        notifyNeighbors();
        return true;
    }

    @Override public boolean isBusy() { return !getMainNode().isActive(); }

    // ==================== 样板管理 ====================

    private int findPatternSlot(@Nonnull IPatternDetails patternDetails) {
        // 必须用 equals 比较（与 MMCE 的 MEPatternProvider 用 List.indexOf 同理）：
        // AE2 的 getPatternForItem 每次都 new PatternHelper(...)，
        // CraftingCPU 持有的 details 实例与我们缓存的实例不是同一个对象，
        // 引用比较（==）会永远失配，导致 pushPattern 全部被拒绝。
        // 同一配方固定使用第一个匹配槽位：推送的材料在该槽位输入缓冲中累积，
        // 由机器并行度决定单次运行消耗多少份，实现"下单 N 份一次发配"。
        for (int i = 0; i < PATTERNS; i++) {
            if (details[i] != null && details[i].equals(patternDetails)) {
                return i;
            }
        }
        return -1;
    }

    public void refreshPatterns() {
        if (world instanceof net.minecraft.world.WorldServer serverWorld && !serverWorld.isCallingFromMinecraftThread()) {
            serverWorld.addScheduledTask(() -> { if (!isInvalid()) refreshPatterns(); });
            return;
        }
        for (int i = 0; i < PATTERNS; i++) {
            refreshPattern(i);
        }
        notifyNeighbors();
    }

    protected void refreshPattern(int index) {
        ItemStack patternStack = patterns.getStackInSlot(index);
        IPatternDetails detail = null;
        if (!patternStack.isEmpty() && world != null) {
            detail = PatternDetailsHelper.decodePattern(patternStack, world);
        }
        details[index] = detail;
        slots[index].setPatternDetails(detail);
    }

    // ==================== Native inventory callbacks ====================

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == patterns) {
            refreshPattern(slot);
            postPatternChangeEvent();
        }
    }
    @Override public void saveChangedInventory(AppEngInternalInventory inv) { markDirty(); }
    @Override public boolean isClientSide() { return world == null || world.isRemote; }

    // ==================== Custom name ====================

    public String getCustomInventoryName() {
        return hasCustomInventoryName() ? customName : I18n.translateToLocal("tile.mmceaddition.me_pattern_assembly.name");
    }

    public boolean hasCustomInventoryName() {
        return customName != null && !customName.isEmpty();
    }

    public void setCustomName(String name) {
        this.customName = name == null ? "" : name;
        markDirty();
    }

    // ==================== MachineComponentTileNotifiable ====================

    @Override
    public void onMachineEvent(MachineEvent event) {
        if (event instanceof MachineStructureUpdateEvent) {
            // 结构更新时刷新 AE 样板，并通知网络重新扫描可合成物品。
            refreshPatterns();
            postPatternChangeEvent();
            // MMCE 在结构破坏后重组时会静默丢弃进行中的配方
            //（onStructureFormed → resetRecipe / 控制器 invalidate 均不投递
            // RecipeFinish/RecipeFailure 事件），此处主动释放槽位锁定与 ThreadLocal，
            // 避免残留的 currentCraftingSlot/activeSlotIndex 让后续无事件复核永远命中
            // 错误的槽位，导致机器陷入持续性闲置。
            clearCurrentCraftingSlot();
            clearActiveSlot();
            return;
        }
        // MMCE 2.2.2 的机器事件不投递到 Forge 事件总线，
        // 而是由 MachineEvent.postEvent() → postEventToComponents() 直接调用结构组件的
        // onMachineEvent，因此配方事件必须在此分发（@Mod.EventBusSubscriber 永远收不到）。
        if (event instanceof RecipeCheckEvent) {
            RecipeCheckEvent check = (RecipeCheckEvent) event;
            if (check.phase == Phase.START) {
                onRecipeCheckStart(check);
            } else {
                onRecipeCheckEnd(check);
            }
        } else if (event instanceof RecipeStartEvent) {
            hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext ctx = ((RecipeStartEvent) event).getContext();
            onRecipeStart(ctx != null ? ctx.getParentRecipe() : null);
        } else if (event instanceof RecipeTickEvent) {
            RecipeTickEvent tick = (RecipeTickEvent) event;
            hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext ctx = tick.getContext();
            if (tick.phase == Phase.START) {
                setActiveSlotForRecipe(ctx != null ? ctx.getParentRecipe() : null);
            } else {
                clearActiveSlot();
            }
        } else if (event instanceof RecipeFinishEvent) {
            onRecipeFinish();
        } else if (event instanceof RecipeFailureEvent) {
            onRecipeFailure();
        }
    }

    private void postPatternChangeEvent() {
        // MMCE posts structure changes from its recipe/structure workers. Native
        // AE2S provider registration is owned exclusively by the server thread.
        if (world instanceof net.minecraft.world.WorldServer serverWorld && !serverWorld.isCallingFromMinecraftThread()) {
            serverWorld.addScheduledTask(() -> { if (!isInvalid()) postPatternChangeEvent(); });
            return;
        }
        ICraftingProvider.requestUpdate(getMainNode());
    }

    // ==================== 催化剂与 RecipeCheckEvent ====================

    /**
     * 由外部事件处理器调用：在 RecipeCheckEvent.START 时匹配当前配方对应的 slot。
     */
    public void onRecipeCheckStart(RecipeCheckEvent event) {
        hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe = event.getContext().getParentRecipe();
        setActiveSlotForRecipe(recipe);

        int slotIndex = activeSlotIndex.get();
        // 记录本次检查匹配的 slot（包括 -1）：
        // 搜索任务在异步线程逐个候选检查，最后检查的候选即胜出的候选，
        // 主线程随后的无事件复核依赖这个值定位到同一 slot。
        lastCheckedSlot = slotIndex;
    }

    /**
     * 由外部事件处理器调用：在 RecipeCheckEvent.END 时清除 active slot。
     */
    public void onRecipeCheckEnd(RecipeCheckEvent event) {
        clearActiveSlot();
    }

    // 当前正在执行的配方锁定的 slot，用于在 RecipeTickEvent 期间快速定位。
    private int currentCraftingSlot = -1;

    // 最近一次配方检查匹配到的 slot（异步搜索线程写入；
    // 主线程在 searchAndStartRecipe 中做无事件的复核时，据此定位到同一个 slot）。
    private volatile int lastCheckedSlot = -1;

    /**
     * 解析聚合 handler 当前应访问的 slot：
     * <ol>
     *   <li>检查/tick 事件窗口内使用 ThreadLocal（窗口由 RecipeCheckEvent、RecipeTickEvent 的 START~END 圈定）；</li>
     *   <li>配方启动消耗（RecipeStartEvent 之后的 startCrafting）回退到 currentCraftingSlot；</li>
     *   <li>主线程无事件的复核回退到最近一次检查匹配的 slot（lastCheckedSlot）；</li>
     *   <li>以上都无效时返回 -1，聚合 handler 对外表现为空。</li>
     * </ol>
     * 注意：必须保证任意时刻只暴露单一 slot 的内容，
     * 否则并行度计算会把分散在多个 slot 的材料合并高估，造成跨槽消耗。
     */
    private int resolveActiveSlot() {
        int active = activeSlotIndex.get();
        if (active >= 0 && active < PATTERNS) {
            return active;
        }
        if (currentCraftingSlot >= 0 && currentCraftingSlot < PATTERNS) {
            return currentCraftingSlot;
        }
        if (lastCheckedSlot >= 0 && lastCheckedSlot < PATTERNS) {
            return lastCheckedSlot;
        }
        return -1;
    }

    /**
     * 在配方执行 tick 期间锁定当前配方对应的 slot，
     * 使 per-tick 的输入消耗和产物输出都能进入同一个槽位。
     */
    public void setActiveSlotForRecipe(@Nullable hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        if (recipe == null) {
            activeSlotIndex.set(-1);
            return;
        }
        // 如果已经有正在执行的配方锁定的 slot，并且它仍然匹配当前配方，则优先使用它。
        if (currentCraftingSlot >= 0 && currentCraftingSlot < PATTERNS
                && slotMatchesRecipe(slots[currentCraftingSlot], recipe)) {
            activeSlotIndex.set(currentCraftingSlot);
            return;
        }
        int slotIndex = findSlotForRecipe(recipe);
        activeSlotIndex.set(slotIndex);

        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} set active slot for recipe {} -> slotIndex={}", getPos(), recipe.getRegistryName(), slotIndex);
        }
    }

    /**
     * 清除检查期/执行期隔离。
     */
    public void clearActiveSlot() {
        activeSlotIndex.remove();
    }

    /**
     * 配方开始：锁定对应 slot，避免执行期间产物落到其他槽位。
     */
    public void onRecipeStart(@Nullable hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        if (recipe == null) {
            clearCurrentCraftingSlot();
            return;
        }
        int slot = findSlotForRecipe(recipe);
        setCurrentCraftingSlot(slot);
        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} recipe start {} -> lockedSlot={}", getPos(), recipe.getRegistryName(), slot);
        }
    }

    /**
     * 配方结束/失败：释放锁定的 slot。
     */
    public void onRecipeFinish() {
        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} recipe finish -> clear lockedSlot={}", getPos(), currentCraftingSlot);
        }
        clearCurrentCraftingSlot();
        // 防御性清除：正常完成路径上 RecipeTickEvent.END 已清过 ThreadLocal，
        // 但 waitForFinish 等路径不一定经过 END，remove() 幂等，重复清除无副作用。
        clearActiveSlot();
    }

    public void onRecipeFailure() {
        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} recipe failure -> clear lockedSlot={}", getPos(), currentCraftingSlot);
        }
        clearCurrentCraftingSlot();
        // 关键修复：MMCE 在 per-tick 检查失败销毁配方时（doRecipeTick → isNotWorking），
        // 只会投递 RecipeFailureEvent，不会投递 RecipeTickEvent.END，
        // 主线程的 activeSlotIndex 因此残留旧槽位。若不清除，机器回到闲置后，
        // 主线程无事件的复核（searchAndStartRecipe → canStartCrafting）会永远命中
        // 残留槽位，导致复核持续失败、机器陷入持续性闲置。
        clearActiveSlot();
    }

    private void setCurrentCraftingSlot(int slot) {
        clearCurrentCraftingSlot();
        if (slot >= 0 && slot < PATTERNS) {
            currentCraftingSlot = slot;
            slots[slot].setActive(true);
        }
    }

    private void clearCurrentCraftingSlot() {
        if (currentCraftingSlot >= 0 && currentCraftingSlot < PATTERNS) {
            slots[currentCraftingSlot].setActive(false);
        }
        currentCraftingSlot = -1;
    }

    private boolean slotMatchesRecipe(PatternAssemblySlot slot, hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        return slotHasInputsForRecipe(slot, recipe) || matchesRecipeByOutputs(slot.getPatternDetails(), recipe);
    }

    private int findSlotForRecipe(hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        // 优先根据已推入该 slot 的输入原料匹配：更可靠，能处理输出物品比较失败的情况。
        for (int i = 0; i < PATTERNS; i++) {
            PatternAssemblySlot slot = slots[i];
            if (slot.hasPattern() && slotHasInputsForRecipe(slot, recipe)) {
                return i;
            }
        }
        // 其次根据配方输出匹配任意拥有对应样板的 slot。
        for (int i = 0; i < PATTERNS; i++) {
            PatternAssemblySlot slot = slots[i];
            if (slot.hasPattern() && matchesRecipeByOutputs(slot.getPatternDetails(), recipe)) {
                return i;
            }
        }
        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} could not find slot for recipe {}", getPos(), recipe.getRegistryName());
        }
        return -1;
    }

    /**
     * 检查某个 slot 的输入缓冲是否包含当前配方所需的所有物品/流体原料。
     */
    private boolean slotHasInputsForRecipe(PatternAssemblySlot slot, hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        if ((slot.getInputItemBuffer() == null || slot.getInputItemBuffer().isEmpty())
                && (slot.getInputFluidBuffer() == null || slot.getInputFluidBuffer().isEmpty())) {
            return false;
        }
        for (hellfirepvp.modularmachinery.common.crafting.helper.ComponentRequirement<?, ?> req : recipe.getCraftingRequirements()) {
            if (req.getActionType() != IOType.INPUT) continue;
            if (req instanceof RequirementCatalyst) {
                // 催化剂不进入输入缓冲，单独处理。
                continue;
            }
            if (req instanceof RequirementItem) {
                RequirementItem itemReq = (RequirementItem) req;
                if (itemReq.chance <= 0) continue;
                if (!checkItemRequirement(slot.getInputItemBuffer(), itemReq)) {
                    return false;
                }
            } else if (req instanceof RequirementIngredientArray) {
                if (((RequirementIngredientArray) req).chance <= 0) continue;
                for (ChancedIngredientStack ingredient : ((RequirementIngredientArray) req).getIngredients()) {
                    if (ingredient == null || ingredient.itemStack == null || ingredient.itemStack.isEmpty()) continue;
                    long available = slot.getInputItemBuffer().getAmount(new ItemVariant(ingredient.itemStack));
                    if (available < ingredient.itemStack.getCount()) {
                        return false;
                    }
                }
            } else if (req instanceof RequirementFluid) {
                RequirementFluid fluidReq = (RequirementFluid) req;
                if (fluidReq.chance <= 0) continue;
                FluidStack required = fluidReq.required;
                if (required == null || required.amount <= 0) continue;
                long available = slot.getInputFluidBuffer().getAmount(required.getFluid());
                if (available < required.amount) {
                    if (MMCEAdditionConfig.debugPatternAssembly) {
                        MMCEAddition.LOGGER.debug("MEPatternAssembly at {} slot fluid check failed for {}: need {} mB of {}, have {} mB",
                                getPos(), recipe.getRegistryName(), required.amount,
                                required.getFluid().getName(), available);
                    }
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkItemRequirement(LongItemBuffer buffer, RequirementItem itemReq) {
        ItemStack required = itemReq.required;
        if (required != null && !required.isEmpty()) {
            long available = buffer.getAmount(new ItemVariant(required));
            return available >= required.getCount();
        }
        if (itemReq.oreDictName != null && !itemReq.oreDictName.isEmpty()) {
            for (ItemStack ore : net.minecraftforge.oredict.OreDictionary.getOres(itemReq.oreDictName)) {
                if (!ore.isEmpty() && buffer.getAmount(new ItemVariant(ore)) >= ore.getCount()) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean matchesRecipeByOutputs(@Nullable IPatternDetails details, hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        if (details == null || recipe == null) return false;
        // 通过输出物品进行匹配：只比较物品种类与 NBT，忽略数量。
        for (GenericStack output : details.getOutputs()) {
            if (!(output.what() instanceof AEItemKey item)) continue;
            ItemStack outputStack = item.toStack();
            for (hellfirepvp.modularmachinery.common.crafting.helper.ComponentRequirement<?, ?> req : recipe.getCraftingRequirements()) {
                if (req.getActionType() != IOType.OUTPUT) continue;
                if (req instanceof RequirementIngredientArray) {
                    for (ChancedIngredientStack ingredient : ((RequirementIngredientArray) req).getIngredients()) {
                        if (ingredient == null || ingredient.itemStack == null || ingredient.itemStack.isEmpty()) continue;
                        if (itemStackMatchesIgnoreCount(ingredient.itemStack, outputStack)) {
                            return true;
                        }
                    }
                } else if (req instanceof RequirementItem) {
                    ItemStack required = ((RequirementItem) req).required;
                    if (required != null && !required.isEmpty()
                            && itemStackMatchesIgnoreCount(required, outputStack)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean itemStackMatchesIgnoreCount(@Nullable ItemStack a, @Nullable ItemStack b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return ItemStack.areItemsEqual(a, b) && ItemStack.areItemStackTagsEqual(a, b);
    }

    // ==================== 输出缓冲管理 ====================

    public void markOutputDirty(PatternAssemblySlot slot) {
        if (!world.isRemote) {
            MEAsyncOutputManager.INSTANCE.markDirty(this);
        }
    }

    public PatternAssemblySlot[] getSlots() {
        return slots;
    }

    public AppEngInternalInventory getPatterns() {
        return patterns;
    }

    public boolean hasAnyOutput() {
        for (PatternAssemblySlot slot : slots) {
            if (!slot.getOutputItemBuffer().isEmpty() || !slot.getOutputFluidBuffer().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ==================== TOP 兼容用状态汇总 ====================

    /**
     * AE 网络是否已连接且激活（有频道、有能源）。
     */
    public boolean isNetworkActive() {
        return getMainNode().isActive();
    }

    /**
     * 已放入样板的槽位数（0 ~ PATTERNS）。
     */
    public int getUsedPatternSlotCount() {
        int used = 0;
        for (int i = 0; i < patterns.size(); i++) {
            if (!patterns.getStackInSlot(i).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    /**
     * 全部样板槽的输入物品缓冲总量（个）。
     */
    public long getTotalBufferedInputItems() {
        long total = 0;
        for (PatternAssemblySlot slot : slots) {
            total += slot.getInputItemBuffer().getTotalAmount();
        }
        return total;
    }

    /**
     * 全部样板槽的输入流体缓冲总量（mB）。
     */
    public long getTotalBufferedInputFluids() {
        long total = 0;
        for (PatternAssemblySlot slot : slots) {
            total += slot.getInputFluidBuffer().getTotalAmount();
        }
        return total;
    }

    /**
     * 全部样板槽的输出物品缓冲总量（个）。
     */
    public long getTotalBufferedOutputItems() {
        long total = 0;
        for (PatternAssemblySlot slot : slots) {
            total += slot.getOutputItemBuffer().getTotalAmount();
        }
        return total;
    }

    /**
     * 全部样板槽的输出流体缓冲总量（mB）。
     */
    public long getTotalBufferedOutputFluids() {
        long total = 0;
        for (PatternAssemblySlot slot : slots) {
            total += slot.getOutputFluidBuffer().getTotalAmount();
        }
        return total;
    }

    // ==================== 生命周期 ====================

    private boolean registered = false;

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            refreshPatterns();
            postPatternChangeEvent();
            if (!registered) {
                MEAsyncOutputManager.INSTANCE.register(this);
                registered = true;
            }
            if (hasAnyOutput()) {
                MEAsyncOutputManager.INSTANCE.markDirty(this);
            }
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (registered) {
            MEAsyncOutputManager.INSTANCE.unregister(this);
            registered = false;
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (registered) {
            MEAsyncOutputManager.INSTANCE.unregister(this);
            registered = false;
        }
    }

    /**
     * 创建能源输出 ProcessingComponent（配方产 RF 时回注 AE 网络）.
     */
    public ProcessingComponent<?> createEnergyOutputProcessingComponent() {
        return wrapComponent(new PatternAssemblyEnergyOutputHatch(this));
    }

    /**
     * 创建网络魔力输入 ProcessingComponent；Botania / AE2Enhanced Mana API 不可用时返回 null.
     */
    @Nullable
    public ProcessingComponent<?> createManaInputProcessingComponent() {
        return wrapComponent(AssemblyResourceComponents.createManaInput(this));
    }

    /**
     * 创建网络星能输入 ProcessingComponent；Astral Sorcery / AE2Enhanced Starlight API 不可用时返回 null.
     */
    @Nullable
    public ProcessingComponent<?> createStarlightInputProcessingComponent() {
        return wrapComponent(AssemblyResourceComponents.createStarlightInput(this));
    }

    /**
     * 创建网络源质输入 ProcessingComponent；Thaumcraft / ThaumicEnergistics 不可用时返回 null.
     */
    @Nullable
    public ProcessingComponent<?> createAspectInputProcessingComponent() {
        return wrapComponent(AssemblyResourceComponents.createAspectInput(this));
    }

    /**
     * 把通配 MachineComponent 包装为 ProcessingComponent；null 输入原样返回 null.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private static ProcessingComponent<?> wrapComponent(@Nullable MachineComponent<?> component) {
        if (component == null) {
            return null;
        }
        return new ProcessingComponent(component, component.getContainerProvider(), null);
    }

    // ==================== NBT 序列化 ====================

    @Override
    public void readCustomNBT(NBTTagCompound compound) {
        super.readCustomNBT(compound);
        if (compound.hasKey("Patterns")) {
            patterns.readFromNBT(compound, "Patterns");
        }
        if (compound.hasKey("Slots")) {
            NBTTagList slotList = compound.getTagList("Slots", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < slotList.tagCount() && i < PATTERNS; i++) {
                slots[i].readFromNBT(slotList.getCompoundTagAt(i));
            }
        }
        if (compound.hasKey("CustomName")) {
            customName = compound.getString("CustomName");
        }
        refreshPatterns();
    }

    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        patterns.writeToNBT(compound, "Patterns");
        NBTTagList slotList = new NBTTagList();
        for (PatternAssemblySlot slot : slots) {
            NBTTagCompound tag = new NBTTagCompound();
            slot.writeToNBT(tag);
            slotList.appendTag(tag);
        }
        compound.setTag("Slots", slotList);
        if (customName != null && !customName.isEmpty()) {
            compound.setString("CustomName", customName);
        }
    }

    // ==================== Capability ====================

    /**
     * 获取 AE2 动作源。
     */
    public IActionSource getSource() {
        return source;
    }

    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                || capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
                || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(aggregatedInputItemHandler);
        }
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(aggregatedInputFluidHandler);
        }
        return super.getCapability(capability, facing);
    }

    // ==================== 聚合输入 Handler（检查期隔离） ====================

    /**
     * 聚合所有 slot 输入的物品 handler。
     * 在没有 activeSlotIndex 时合并所有 slot；有 activeSlotIndex 时只访问对应 slot。
     */
    private class AggregatedInputItemHandler extends IItemHandlerImpl {

        AggregatedInputItemHandler() {
            // 基类需要数组初始化，但实际数据来自 slot handler。
            // 数组尺寸与 getSlots() 保持一致，避免 MMCE 内部直接遍历基类数组时越界。
            this.allowAnySlots = false;
            this.accessibleSides = new EnumFacing[0];
            this.slotLimits = new int[200];
            this.inventory = new SlotStackHolder[200];
            this.inSlots = new int[200];
            this.outSlots = new int[200];
            for (int i = 0; i < 200; i++) {
                this.slotLimits[i] = Integer.MAX_VALUE;
                this.inventory[i] = new SlotStackHolder(i);
                this.inventory[i].itemStack.set(ItemStack.EMPTY);
                this.inSlots[i] = i;
                this.outSlots[i] = i;
            }
            this.miscSlots = new int[0];
        }

        @Override
        public int getSlots() {
            return 200;
        }

        @Override
        public IItemHandlerImpl copy() {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                LongItemBuffer copyBuffer = new LongItemBuffer();
                copyBuffer.readFromNBT(bufferToNbt(slots[active].getInputItemBuffer()));
                LongInputItemHandler copy = new LongInputItemHandler(copyBuffer);
                copy.syncWithBuffer();
                // 把该槽的催化剂作为只读展示槽附加到检查副本上：
                // RequirementCatalyst 在配方检查/并行度计算中据此确认催化剂存在并注入 modifier，
                // 真实 handler 不暴露催化剂，chance=0 也保证其永不被消耗。
                ItemStack[] catalysts = slots[active].getCatalysts();
                if (catalysts != null && catalysts.length > 0) {
                    List<ItemStack> view = new ArrayList<>(catalysts.length);
                    for (ItemStack catalyst : catalysts) {
                        if (!catalyst.isEmpty()) {
                            // 只展示 1 个：催化剂是"存在性"需求，展示实际数量会污染
                            // 同物品输入需求的副本计数（检查副本与真实消耗必须一致）
                            ItemStack single = catalyst.copy();
                            single.setCount(1);
                            view.add(single);
                        }
                    }
                    if (!view.isEmpty()) {
                        copy.setCatalystView(view.toArray(new ItemStack[0]));
                    }
                }
                return copy;
            }
            LongInputItemHandler empty = new LongInputItemHandler(new LongItemBuffer());
            empty.syncWithBuffer();
            return empty;
        }

        @Override
        public IItemHandlerImpl fastCopy() {
            return copy();
        }

        @Nonnull
        @Override
        public net.minecraftforge.items.IItemHandlerModifiable asGUIAccess() {
            return this;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputItemHandler().getStackInSlot(slot);
            }
            return ItemStack.EMPTY;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputItemHandler().insertItem(slot, stack, simulate);
            }
            // 无 active slot 时，尝试写入第一个有空间的 slot（用于非配方上下文）
            for (PatternAssemblySlot patternSlot : slots) {
                ItemStack remainder = patternSlot.getInputItemHandler().insertItem(slot, stack, simulate);
                if (remainder.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                stack = remainder;
            }
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputItemHandler().extractItem(slot, amount, simulate);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                if (MMCEAdditionConfig.debugPatternAssembly) {
                    // 真实消耗/写入的记账日志（副本是独立实例，不会走到这里）
                    LongItemBuffer buf = slots[active].getInputItemBuffer();
                    MMCEAddition.LOGGER.debug(
                            "MEPatternAssembly at {} input write: slot={}, stack={}, bufferNow={}",
                            getPos(), active, stack, buf.snapshot());
                }
                slots[active].getInputItemHandler().setStackInSlot(slot, stack);
            }
        }

        private net.minecraft.nbt.NBTTagCompound bufferToNbt(LongItemBuffer buffer) {
            net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
            buffer.writeToNBT(tag);
            return tag;
        }
    }

    private class AggregatedInputFluidHandler implements IFluidHandler {

        @Override
        public net.minecraftforge.fluids.capability.IFluidTankProperties[] getTankProperties() {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputFluidHandler().getTankProperties();
            }
            return new net.minecraftforge.fluids.capability.IFluidTankProperties[0];
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputFluidHandler().fill(resource, doFill);
            }
            for (PatternAssemblySlot patternSlot : slots) {
                int filled = patternSlot.getInputFluidHandler().fill(resource, doFill);
                if (filled > 0) return filled;
            }
            return 0;
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputFluidHandler().drain(resource, doDrain);
            }
            return null;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getInputFluidHandler().drain(maxDrain, doDrain);
            }
            return null;
        }
    }

    /**
     * 聚合所有 slot 输出的物品 handler。
     * 在检查期会把产物写入当前 active slot 的输出缓冲。
     */
    private class AggregatedOutputItemHandler extends IItemHandlerImpl {

        AggregatedOutputItemHandler() {
            this.allowAnySlots = false;
            this.accessibleSides = new EnumFacing[0];
            this.slotLimits = new int[200];
            this.inventory = new SlotStackHolder[200];
            this.inSlots = new int[200];
            this.outSlots = new int[200];
            for (int i = 0; i < 200; i++) {
                this.slotLimits[i] = Integer.MAX_VALUE;
                this.inventory[i] = new SlotStackHolder(i);
                this.inventory[i].itemStack.set(ItemStack.EMPTY);
                this.inSlots[i] = i;
                this.outSlots[i] = i;
            }
            this.miscSlots = new int[0];
        }

        @Override
        public int getSlots() {
            return 200;
        }

        @Override
        public IItemHandlerImpl copy() {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                LongItemBuffer copyBuffer = new LongItemBuffer();
                copyBuffer.readFromNBT(bufferToNbt(slots[active].getOutputItemBuffer()));
                return new LongBufferItemHandler(copyBuffer);
            }
            return new LongBufferItemHandler(new LongItemBuffer());
        }

        @Override
        public IItemHandlerImpl fastCopy() {
            return copy();
        }

        @Nonnull
        @Override
        public net.minecraftforge.items.IItemHandlerModifiable asGUIAccess() {
            return this;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getOutputItemHandler().getStackInSlot(slot);
            }
            return ItemStack.EMPTY;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getOutputItemHandler().insertItem(slot, stack, simulate);
            }
            for (PatternAssemblySlot patternSlot : slots) {
                ItemStack remainder = patternSlot.getOutputItemHandler().insertItem(slot, stack, simulate);
                if (remainder.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                stack = remainder;
            }
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            // 输出仓不允许外部抽取
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                if (MMCEAdditionConfig.debugPatternAssembly && !stack.isEmpty()) {
                    // 真实产出写入的记账日志（副本是独立实例，不会走到这里）
                    LongItemBuffer buf = slots[active].getOutputItemBuffer();
                    MMCEAddition.LOGGER.debug(
                            "MEPatternAssembly at {} output write: slot={}, stack={} x{}, bufferNow={}",
                            getPos(), active, stack.getDisplayName(), stack.getCount(), buf.snapshot());
                }
                slots[active].getOutputItemHandler().setStackInSlot(slot, stack);
                return;
            }
            // MMCE 的 ItemUtils.insertAll 通过 setStackInSlot 落产物；
            // 无法定位槽位时（极端时序）绝不能静默丢弃，回退写入 0 号槽的输出缓冲。
            if (!stack.isEmpty()) {
                if (MMCEAdditionConfig.debugPatternAssembly) {
                    MMCEAddition.LOGGER.debug("MEPatternAssembly at {} output setStackInSlot with no active slot, fallback to slot 0: {} x{}",
                            getPos(), stack.getDisplayName(), stack.getCount());
                }
                slots[0].getOutputItemHandler().setStackInSlot(slot, stack);
            }
        }

        private net.minecraft.nbt.NBTTagCompound bufferToNbt(LongItemBuffer buffer) {
            net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
            buffer.writeToNBT(tag);
            return tag;
        }
    }

    /**
     * 聚合所有 slot 输出的流体 handler。
     */
    private class AggregatedOutputFluidHandler implements IFluidHandler {

        @Override
        public net.minecraftforge.fluids.capability.IFluidTankProperties[] getTankProperties() {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getOutputFluidHandler().getTankProperties();
            }
            return new net.minecraftforge.fluids.capability.IFluidTankProperties[0];
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            int active = resolveActiveSlot();
            if (active >= 0 && active < PATTERNS) {
                return slots[active].getOutputFluidHandler().fill(resource, doFill);
            }
            for (PatternAssemblySlot patternSlot : slots) {
                int filled = patternSlot.getOutputFluidHandler().fill(resource, doFill);
                if (filled > 0) return filled;
            }
            return 0;
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            return null;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            return null;
        }
    }
}

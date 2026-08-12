package com.github.aeddddd.mmceaddition.tile;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.ICustomNameObject;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.api.networking.security.IActionSource;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.inv.IAEAppEngInventory;
import appeng.util.inv.InvOperation;
import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.compat.NetworkEnergyCompat;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
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
import appeng.api.implementations.ICraftingPatternItem;
import github.kasuminova.mmce.common.tile.base.MEMachineComponent;
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
public class TileMEPatternAssembly extends MEMachineComponent
        implements MachineComponentTile,
                   MachineComponentTileNotifiable,
                   ICraftingProvider,
                   IAEAppEngInventory,
                   ICustomNameObject {

    public static final int PATTERNS = 36;
    public static final int SLOT_CATALYST_COUNT = PatternAssemblySlot.CATALYST_SLOTS;

    // 36 个样板槽（使用 AppEngInternalInventory 保持与原版一致）
    protected final AppEngInternalInventory patterns;

    // 36 个 PatternAssemblySlot
    protected final PatternAssemblySlot[] slots = new PatternAssemblySlot[PATTERNS];

    // 样板解析缓存：pattern slot index -> ICraftingPatternDetails
    protected final ICraftingPatternDetails[] details = new ICraftingPatternDetails[PATTERNS];

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
        this.patterns = new AppEngInternalInventory(this, PATTERNS);
        for (int i = 0; i < PATTERNS; i++) {
            this.slots[i] = new PatternAssemblySlot(i, this);
        }
        this.aggregatedInputItemHandler = new AggregatedInputItemHandler();
        this.aggregatedInputFluidHandler = new AggregatedInputFluidHandler();
        this.aggregatedOutputItemHandler = new AggregatedOutputItemHandler();
        this.aggregatedOutputFluidHandler = new AggregatedOutputFluidHandler();
    }

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
     * 网络 RF 能源处理器。
     * <p>
     * 实现 MMCE 的 long 级异步能源接口，把"当前能量/提取能量"直接映射为
     * 对 AE 网络 RF 存量的查询与扣除（经 {@link NetworkEnergyCompat} 反射调用 AE2Enhanced API）：
     * <ul>
     *   <li>{@link #getCurrentEnergy()}：网络 RF 存量</li>
     *   <li>{@link #extractEnergy(long)}：全有或全无——先模拟确认存量充足再实际扣除，
     *       避免 RequirementEnergy 把部分提取误判为成功</li>
     *   <li>{@link #setCurrentEnergy(long)}：空实现——异步路径不会调用；网络能量不支持本地写回</li>
     *   <li>{@link #receiveEnergy(long)}：恒 false——只作为能源输入，不接收外部能量</li>
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
            return false;
        }
    }

    // ==================== ICraftingProvider ====================

    @Override
    public void provideCrafting(@Nonnull ICraftingProviderHelper helper) {
        for (int i = 0; i < PATTERNS; i++) {
            ICraftingPatternDetails detail = details[i];
            if (detail != null) {
                helper.addCraftingOption(this, detail);
            }
        }
    }

    @Override
    public boolean pushPattern(@Nonnull ICraftingPatternDetails patternDetails, @Nonnull InventoryCrafting table) {
        if (!proxy.isActive()) {
            return false;
        }

        int slotIndex = findPatternSlot(patternDetails);
        if (slotIndex < 0) {
            return false;
        }

        // 同一配方的材料允许累积到同一槽位（与原版 ME 供应器的共享缓冲同理）：
        // CPU 单 tick 内可连续多次推送同一配方，输入缓冲累积全部材料，
        // 机器端再由并行度决定单次运行消耗多少份，
        // 避免"推一份拒一次"导致 CPU 反复调度卡顿。
        PatternAssemblySlot slot = slots[slotIndex];

        // 收集 InventoryCrafting 中的物品与流体（流体经 AE2FC 假物品解码）
        List<ItemStack> inputs = new ArrayList<>();
        List<FluidStack> fluidInputs = new ArrayList<>();
        for (int i = 0; i < table.getSizeInventory(); i++) {
            ItemStack stack = table.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            FluidStack fluid = decodeFluidInput(stack);
            if (fluid != null) {
                fluidInputs.add(fluid);
            } else {
                inputs.add(stack);
            }
        }
        slot.pushPattern(inputs.toArray(new ItemStack[0]));
        if (!fluidInputs.isEmpty()) {
            slot.pushPatternFluid(fluidInputs.toArray(new FluidStack[0]));
        }

        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} pushed pattern into slot {} with {} item inputs, {} fluid inputs",
                    getPos(), slotIndex, inputs.size(), fluidInputs.size());
        }

        // 触发控制器立即尝试启动配方
        notifyNeighbors();

        return true;
    }

    @Override
    public boolean isBusy() {
        // 同一配方的推送总是累积到该配方已有的槽位，不同配方各占独立槽位，
        // 因此只要 CPU 推送的是我们声明过的样板就一定能接收；
        // 返回 true 只会让 CPU 跳过本 provider，人为拖慢发配。
        return false;
    }

    // ==================== 样板管理 ====================

    /** AE2FC（AE2 Fluid Crafting）是否加载：流体样板输入解码依赖它的假物品 API。 */
    private static final boolean AE2FC_LOADED = net.minecraftforge.fml.common.Loader.isModLoaded("ae2fc");

    /**
     * 如果给定的物品堆是 AE2FC 的流体假物品（流体样板推送时的流体载体），
     * 则解码为 {@link FluidStack}；否则返回 null。
     * <p>
     * 与 MMCE 原版 ME Pattern Provider 的处理方式一致：
     * 仅在 AE2FC 加载时才引用其类，保证软依赖安全。
     */
    @Nullable
    private static FluidStack decodeFluidInput(@Nonnull ItemStack stack) {
        if (!AE2FC_LOADED) {
            return null;
        }
        if (!com.glodblock.github.common.item.fake.FakeFluids.isFluidFakeItem(stack)) {
            return null;
        }
        return com.glodblock.github.common.item.fake.FakeItemRegister.getStack(stack);
    }

    private int findPatternSlot(@Nonnull ICraftingPatternDetails patternDetails) {
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
        for (int i = 0; i < PATTERNS; i++) {
            refreshPattern(i);
        }
        notifyNeighbors();
    }

    protected void refreshPattern(int index) {
        ItemStack patternStack = patterns.getStackInSlot(index);
        ICraftingPatternDetails detail = null;
        if (!patternStack.isEmpty() && patternStack.getItem() instanceof ICraftingPatternItem) {
            detail = ((ICraftingPatternItem) patternStack.getItem()).getPatternForItem(patternStack, world);
        }
        details[index] = detail;
        slots[index].setPatternDetails(detail);
    }

    // ==================== IAEAppEngInventory ====================

    @Override
    public void onChangeInventory(net.minecraftforge.items.IItemHandler inv, int slot, InvOperation op, ItemStack removed, ItemStack added) {
        if (inv == patterns) {
            refreshPattern(slot);
            if (proxy.isReady()) {
                try {
                    proxy.getGrid().postEvent(new MENetworkCraftingPatternChange(this, proxy.getNode()));
                } catch (GridAccessException ignored) {
                }
            }
        }
    }

    @Override
    public void saveChanges() {
        markDirty();
    }

    // ==================== ICustomNameObject ====================

    @Override
    public String getCustomInventoryName() {
        return hasCustomInventoryName() ? customName : I18n.translateToLocal("tile.mmceaddition.me_pattern_assembly.name");
    }

    @Override
    public boolean hasCustomInventoryName() {
        return customName != null && !customName.isEmpty();
    }

    @Override
    public void setCustomName(String name) {
        this.customName = name;
    }

    // ==================== MachineComponentTileNotifiable ====================

    @Override
    public void onMachineEvent(MachineEvent event) {
        if (event instanceof MachineStructureUpdateEvent) {
            // 结构更新时刷新 AE 样板，并通知网络重新扫描可合成物品。
            refreshPatterns();
            postPatternChangeEvent();
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
        if (proxy.isReady()) {
            try {
                proxy.getGrid().postEvent(new MENetworkCraftingPatternChange(this, proxy.getNode()));
            } catch (GridAccessException ignored) {
            }
        }
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
        if (slotIndex >= 0) {
            applyCatalysts(event, slots[slotIndex]);
        }
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
    }

    public void onRecipeFailure() {
        if (MMCEAdditionConfig.debugPatternAssembly) {
            MMCEAddition.LOGGER.debug("MEPatternAssembly at {} recipe failure -> clear lockedSlot={}", getPos(), currentCraftingSlot);
        }
        clearCurrentCraftingSlot();
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

    private boolean matchesRecipeByOutputs(@Nullable ICraftingPatternDetails details, hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe) {
        if (details == null || recipe == null) return false;
        // 通过输出物品进行匹配：只比较物品种类与 NBT，忽略数量。
        for (IAEItemStack output : details.getCondensedOutputs()) {
            ItemStack outputStack = output.createItemStack();
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

    private void applyCatalysts(RecipeCheckEvent event, PatternAssemblySlot slot) {
        if (!slot.hasAnyCatalyst()) return;

        hellfirepvp.modularmachinery.common.crafting.helper.RecipeCraftingContext context = event.getContext();
        hellfirepvp.modularmachinery.common.crafting.MachineRecipe recipe = context.getParentRecipe();
        if (recipe == null) return;

        // 构造一个只包含催化剂物品的虚拟 component 列表。
        // RequirementCatalyst 的 canStartCrafting 需要看到原料存在才会添加 modifier。
        // 催化剂 chance=0，不会真正被消耗，所以虚拟 handler 不减少实际库存。
        List<hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<?>> virtualComponents =
                createCatalystComponents(slot);
        if (virtualComponents.isEmpty()) return;

        for (hellfirepvp.modularmachinery.common.crafting.helper.ComponentRequirement<?, ?> req : recipe.getCraftingRequirements()) {
            if (req.getActionType() != IOType.INPUT) continue;
            if (req instanceof hellfirepvp.modularmachinery.common.crafting.requirement.RequirementCatalyst) {
                hellfirepvp.modularmachinery.common.crafting.requirement.RequirementCatalyst catalystReq =
                        (hellfirepvp.modularmachinery.common.crafting.requirement.RequirementCatalyst) req;
                catalystReq.canStartCrafting(virtualComponents, context);
            }
        }
    }

    private List<hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<?>> createCatalystComponents(PatternAssemblySlot slot) {
        List<hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<?>> components = new ArrayList<>();
        for (ItemStack catalyst : slot.getCatalysts()) {
            if (catalyst.isEmpty()) continue;
            net.minecraftforge.items.IItemHandlerModifiable virtualHandler = new CatalystItemHandler(catalyst);
            MachineComponent.ItemBus component = new MachineComponent.ItemBus(IOType.INPUT) {
                @Nonnull
                @Override
                public net.minecraftforge.items.IItemHandlerModifiable getContainerProvider() {
                    return virtualHandler;
                }
            };
            @SuppressWarnings("unchecked")
            hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<?> pc =
                    new hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent<>(
                            component, virtualHandler, null);
            components.add(pc);
        }
        return components;
    }

    /**
     * 只读虚拟物品 handler，用于向 RequirementCatalyst 展示催化剂存在。
     * 由于催化剂 chance=0，MMCE 不会真正消耗物品，因此 extractItem 返回物品但不修改库存。
     */
    private static class CatalystItemHandler implements net.minecraftforge.items.IItemHandlerModifiable {
        private final ItemStack catalyst;

        CatalystItemHandler(@Nonnull ItemStack catalyst) {
            this.catalyst = catalyst.copy();
            this.catalyst.setCount(1);
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? catalyst.copy() : ItemStack.EMPTY;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            return stack.copy();
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0 && amount > 0) {
                ItemStack result = catalyst.copy();
                result.setCount(Math.min(amount, catalyst.getCount()));
                return result;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
            // 虚拟 handler 不接受设置
        }
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

    // ==================== 生命周期 ====================

    private boolean registered = false;

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
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

    // ==================== NBT 序列化 ====================

    @Override
    public void readCustomNBT(NBTTagCompound compound) {
        super.readCustomNBT(compound);
        if (compound.hasKey("Patterns")) {
            patterns.readFromNBT(compound.getCompoundTag("Patterns"));
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
                slots[active].getOutputItemHandler().setStackInSlot(slot, stack);
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

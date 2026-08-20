package com.github.aeddddd.mmceaddition.virtual;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.TaggedPositionBlockArray;
import hellfirepvp.modularmachinery.common.modifier.SingleBlockModifierReplacement;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import hellfirepvp.modularmachinery.common.util.IBlockStateDescriptor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 机器材料清单生成器。
 * <p>
 * 把一台 MMCE 机器的结构（{@link DynamicMachine#getPattern()}）折算为装配材料清单：
 * <ul>
 *   <li>控制器 ×1（{@code modularmachinery:blockcontroller}）</li>
 *   <li>结构中每个位置按其允许的方块集合生成一个原料组；
 *       仓室方块从候选中剔除，剔除后无剩余候选的位置视为纯仓室位置，整组跳过</li>
 *   <li>歧义位置保留全部候选，装配时任意一种均可抵扣</li>
 *   <li>候选集合完全相同的原料组合并计数</li>
 * </ul>
 * 同时提取机器的单方块升级定义（{@code modifiers} 块）：每个升级占据一个结构位置，
 * 装配时缓存槽中的升级方块会按"替换式"自动顶替该位置的基础材料并记录进机器数据。
 * 机器 JSON 重载（/mm reload）后机器对象被重建，缓存按对象身份失效。
 */
public final class MachineMaterialAnalyzer {

    /** 一个原料组：candidates 中任意一种物品均可抵扣 count 份需求。 */
    public static final class IngredientGroup {
        private final List<ItemStack> candidates;
        private final int count;

        public IngredientGroup(List<ItemStack> candidates, int count) {
            this.candidates = candidates;
            this.count = count;
        }

        @Nonnull
        public List<ItemStack> getCandidates() {
            return candidates;
        }

        public int getCount() {
            return count;
        }

        public boolean accepts(@Nonnull ItemStack stack) {
            return candidatesAccept(candidates, stack);
        }
    }

    /**
     * 一条单方块升级定义：结构中被该升级替换的位置所属原料组 + 升级方块候选。
     * baseGroupIndex = -1 表示该位置不在基础结构中（纯附加，不顶替基础材料）。
     */
    public static final class UpgradeInfo {
        private final String modifierName;
        private final String displayName;
        private final List<ItemStack> candidates;
        private final int baseGroupIndex;

        public UpgradeInfo(String modifierName, String displayName, List<ItemStack> candidates, int baseGroupIndex) {
            this.modifierName = modifierName;
            this.displayName = displayName;
            this.candidates = candidates;
            this.baseGroupIndex = baseGroupIndex;
        }

        @Nonnull
        public String getModifierName() {
            return modifierName;
        }

        @Nonnull
        public String getDisplayName() {
            return displayName;
        }

        @Nonnull
        public List<ItemStack> getCandidates() {
            return candidates;
        }

        public int getBaseGroupIndex() {
            return baseGroupIndex;
        }

        public boolean accepts(@Nonnull ItemStack stack) {
            return candidatesAccept(candidates, stack);
        }
    }

    /**
     * 候选匹配（逐级放宽）：
     * <ol>
     *   <li>物品+meta+NBT 精确匹配</li>
     *   <li>物品+meta 匹配（忽略 NBT，结构候选不含 NBT，玩家物品可能附带）</li>
     *   <li>仅物品匹配（忽略 meta 与 NBT，兼容 damageDropped 与物品形态 meta 不一致的方块）</li>
     * </ol>
     */
    private static boolean candidatesAccept(@Nonnull List<ItemStack> candidates, @Nonnull ItemStack stack) {
        ItemVariant variant = new ItemVariant(stack);
        for (ItemStack candidate : candidates) {
            if (new ItemVariant(candidate).equals(variant)) {
                return true;
            }
        }
        for (ItemStack candidate : candidates) {
            if (candidate.getItem() == stack.getItem() && candidate.getMetadata() == stack.getMetadata()) {
                return true;
            }
        }
        for (ItemStack candidate : candidates) {
            if (candidate.getItem() == stack.getItem()) {
                return true;
            }
        }
        return false;
    }

    /** 机器对象身份 → 材料清单缓存。 */
    private static final Map<DynamicMachine, List<IngredientGroup>> CACHE = new WeakHashMap<>();
    /** 机器对象身份 → 升级定义缓存。 */
    private static final Map<DynamicMachine, List<UpgradeInfo>> UPGRADE_CACHE = new WeakHashMap<>();

    private MachineMaterialAnalyzer() {
    }

    /**
     * 判断该机器是否被虚拟并行黑名单屏蔽（黑名单机器不可生成机器数据）。
     */
    public static boolean isBlacklisted(@Nonnull DynamicMachine machine) {
        String fullName = machine.getRegistryName().toString();
        String path = machine.getRegistryName().getPath();
        for (String entry : MMCEAdditionConfig.virtualParallelMachineBlacklist) {
            if (entry.equals(fullName) || entry.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成（或从缓存读取）该机器的装配材料清单。
     * 黑名单机器与无结构的机器返回空列表。
     */
    @Nonnull
    public static List<IngredientGroup> analyze(@Nonnull DynamicMachine machine) {
        synchronized (CACHE) {
            List<IngredientGroup> cached = CACHE.get(machine);
            if (cached != null) {
                return cached;
            }
            AnalysisResult result = doAnalyze(machine);
            CACHE.put(machine, result.groups);
            UPGRADE_CACHE.put(machine, result.upgrades);
            return result.groups;
        }
    }

    /**
     * 该机器的单方块升级定义列表（需先 analyze 过；未 analyze 时即时分析）。
     */
    @Nonnull
    public static List<UpgradeInfo> upgradesFor(@Nonnull DynamicMachine machine) {
        synchronized (CACHE) {
            List<UpgradeInfo> cached = UPGRADE_CACHE.get(machine);
            if (cached != null) {
                return cached;
            }
            analyze(machine);
            return UPGRADE_CACHE.getOrDefault(machine, Collections.emptyList());
        }
    }

    private static final class AnalysisResult {
        List<IngredientGroup> groups = Collections.emptyList();
        List<UpgradeInfo> upgrades = Collections.emptyList();
    }

    @Nonnull
    private static AnalysisResult doAnalyze(@Nonnull DynamicMachine machine) {
        AnalysisResult result = new AnalysisResult();
        if (isBlacklisted(machine)) {
            return result;
        }

        // 候选集合签名 → 聚合中的原料组
        Map<String, GroupBuilder> groups = new LinkedHashMap<>();
        // 结构位置 → 所属原料组签名（供升级定位替换目标）
        Map<BlockPos, String> positionGroup = new LinkedHashMap<>();

        // 控制器本身不在结构 pattern 中，单独计 1 份
        ItemStack controller = controllerStack();
        if (controller != null) {
            groups.put(signatureOf(singletonVariant(controller)),
                    new GroupBuilder(new ArrayList<>(Collections.singletonList(controller)), 1));
        }

        TaggedPositionBlockArray pattern = machine.getPattern();
        if (pattern == null) {
            result.groups = finish(groups);
            return result;
        }

        for (Map.Entry<BlockPos, BlockArray.BlockInformation> entry : pattern.getPattern().entrySet()) {
            BlockArray.BlockInformation info = entry.getValue();
            if (info == null || info.getMatchingStates() == null) {
                continue;
            }
            Map<ItemVariant, ItemStack> candidates = new LinkedHashMap<>();
            for (IBlockStateDescriptor descriptor : info.getMatchingStates()) {
                if (descriptor == null || descriptor.getApplicable() == null) {
                    continue;
                }
                for (IBlockState applicable : descriptor.getApplicable()) {
                    if (applicable == null) {
                        continue;
                    }
                    ItemStack stack = toStack(applicable);
                    if (stack == null) {
                        continue;
                    }
                    candidates.putIfAbsent(new ItemVariant(stack), stack);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            String signature = signatureOf(candidates.keySet());
            GroupBuilder builder = groups.get(signature);
            if (builder == null) {
                groups.put(signature, new GroupBuilder(new ArrayList<>(candidates.values()), 1));
            } else {
                builder.count++;
            }
            positionGroup.put(entry.getKey(), signature);
        }

        List<IngredientGroup> groupList = finish(groups);
        // 签名 → 原料组下标（供升级定位）
        Map<String, Integer> groupIndex = new LinkedHashMap<>();
        int idx = 0;
        for (String signature : groups.keySet()) {
            groupIndex.put(signature, idx++);
        }
        result.groups = groupList;

        // 提取单方块升级定义（替换式：升级方块顶替被替换位置的基础材料）
        List<UpgradeInfo> upgrades = new ArrayList<>();
        Set<String> seenUpgrades = new HashSet<>();
        for (List<SingleBlockModifierReplacement> list : machine.getModifiers().values()) {
            for (SingleBlockModifierReplacement rep : list) {
                if (rep == null || rep.getBlockInformation() == null || rep.getPos() == null) {
                    continue;
                }
                // 同名升级可能定义在多个位置：每位置一条（顶替各自的基础材料），按 位置+名称 去重
                String key = rep.getModifierName() + "@" + rep.getPos();
                if (!seenUpgrades.add(key)) {
                    continue;
                }
                Map<ItemVariant, ItemStack> candidates = new LinkedHashMap<>();
                if (rep.getBlockInformation().getMatchingStates() != null) {
                    for (IBlockStateDescriptor descriptor : rep.getBlockInformation().getMatchingStates()) {
                        if (descriptor == null || descriptor.getApplicable() == null) {
                            continue;
                        }
                        for (IBlockState applicable : descriptor.getApplicable()) {
                            if (applicable == null) {
                                continue;
                            }
                            ItemStack stack = toStack(applicable);
                            if (stack != null) {
                                candidates.putIfAbsent(new ItemVariant(stack), stack);
                            }
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                String sig = positionGroup.get(rep.getPos());
                int baseGroup = sig == null ? -1 : groupIndex.getOrDefault(sig, -1);
                String display = rep.getDescriptionLines() != null && !rep.getDescriptionLines().isEmpty()
                        ? rep.getDescriptionLines().get(0) : rep.getModifierName();
                upgrades.add(new UpgradeInfo(rep.getModifierName(), display,
                        new ArrayList<>(candidates.values()), baseGroup));
            }
        }
        result.upgrades = Collections.unmodifiableList(upgrades);

        if (MMCEAdditionConfig.debugVirtualParallel) {
            MMCEAddition.LOGGER.info("虚拟装配: 机器 {} 材料清单 {} 组, 升级定义 {} 条",
                    machine.getRegistryName(), groupList.size(), upgrades.size());
        }
        return result;
    }

    @Nonnull
    private static List<IngredientGroup> finish(Map<String, GroupBuilder> groups) {
        List<IngredientGroup> result = new ArrayList<>(groups.size());
        for (GroupBuilder builder : groups.values()) {
            result.add(new IngredientGroup(builder.candidates, builder.count));
        }
        return Collections.unmodifiableList(result);
    }

    private static final class GroupBuilder {
        final List<ItemStack> candidates;
        int count;

        GroupBuilder(List<ItemStack> candidates, int count) {
            this.candidates = candidates;
            this.count = count;
        }
    }

    /**
     * 方块状态 → 物品形态；仓室方块与被移除的方块返回 null。
     */
    @Nullable
    private static ItemStack toStack(@Nonnull IBlockState state) {
        Block block = state.getBlock();
        ResourceLocation regName = block.getRegistryName();
        if (regName == null || isHatchBlock(regName.toString())) {
            return null;
        }
        Item item = Item.getItemFromBlock(block);
        if (item == null || item == Items.AIR) {
            return null;
        }
        int meta;
        try {
            meta = block.damageDropped(state);
        } catch (Exception e) {
            meta = 0;
        }
        // 无子类型物品的 meta 只承载朝向等状态信息（物品形态恒为 meta 0），归一化以匹配玩家持有的物品
        if (!item.getHasSubtypes()) {
            meta = 0;
        }
        return new ItemStack(item, 1, meta);
    }

    /**
     * 判断注册名是否属于 MMCE 的各类仓室方块（输入/输出总线、流体仓、能源仓、ME 样板供应器等）。
     */
    public static boolean isHatchBlock(@Nullable String regName) {
        if (regName == null || !regName.startsWith("modularmachinery:")) {
            return false;
        }
        String path = regName.substring("modularmachinery:".length());
        return path.startsWith("blockinputbus")
                || path.startsWith("blockoutputbus")
                || path.startsWith("blockfluidinputhatch")
                || path.startsWith("blockfluidoutputhatch")
                || path.startsWith("blockenergyinputhatch")
                || path.startsWith("blockenergyoutputhatch")
                || path.startsWith("blockmeiteminputbus")
                || path.startsWith("blockmeitemoutputbus")
                || path.startsWith("blockmefluidinputbus")
                || path.startsWith("blockmefluidoutputbus")
                || path.startsWith("blockmepatternprovider");
    }

    @Nullable
    private static ItemStack controllerStack() {
        Block controller = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("modularmachinery", "blockcontroller"));
        if (controller == null) {
            return null;
        }
        return new ItemStack(controller, 1);
    }

    @Nonnull
    private static Set<ItemVariant> singletonVariant(@Nonnull ItemStack stack) {
        Set<ItemVariant> set = new HashSet<>();
        set.add(new ItemVariant(stack));
        return set;
    }

    /**
     * 候选集合签名：按注册名:meta 排序拼接，保证相同候选集合的签名稳定一致。
     */
    @Nonnull
    private static String signatureOf(@Nonnull java.util.Collection<ItemVariant> variants) {
        List<String> keys = new ArrayList<>(variants.size());
        for (ItemVariant variant : variants) {
            ItemStack stack = variant.toSingleStack();
            ResourceLocation reg = stack.getItem().getRegistryName();
            keys.add((reg == null ? "?" : reg.toString()) + "@" + stack.getMetadata());
        }
        Collections.sort(keys);
        return String.join("|", keys);
    }
}

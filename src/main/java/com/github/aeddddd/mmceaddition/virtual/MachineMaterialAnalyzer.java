package com.github.aeddddd.mmceaddition.virtual;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.util.ItemVariant;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.TaggedPositionBlockArray;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import hellfirepvp.modularmachinery.common.util.IBlockStateDescriptor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器材料清单生成器。
 * <p>
 * 把一台 MMCE 机器的结构（{@link DynamicMachine#getPattern()}）折算为装配材料清单：
 * <ul>
 *   <li>控制器 ×1（{@code modularmachinery:blockcontroller}）</li>
 *   <li>结构中每个位置按其允许的方块集合生成一个原料组；
 *       仓室方块（各类输入/输出总线、流体仓、能源仓、ME 样板供应器）从候选中剔除，
 *       剔除后无剩余候选的位置视为纯仓室位置，整组跳过</li>
 *   <li>歧义位置（一个位置允许多种方块）保留全部候选，装配时任意一种均可抵扣</li>
 *   <li>候选集合完全相同的原料组合并计数（如 500 个外壳位置合并为 外壳 x500）</li>
 * </ul>
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

        /** 候选物品列表（多选一语义）。 */
        @Nonnull
        public List<ItemStack> getCandidates() {
            return candidates;
        }

        /** 单台机器需要的数量。 */
        public int getCount() {
            return count;
        }

        /** 给定物品是否能抵扣本组需求。 */
        public boolean accepts(@Nonnull ItemStack stack) {
            ItemVariant variant = new ItemVariant(stack);
            for (ItemStack candidate : candidates) {
                if (new ItemVariant(candidate).equals(variant)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 机器对象身份 → 材料清单缓存（机器对象在 /mm reload 后重建，缓存自然失效）。 */
    private static final Map<DynamicMachine, List<IngredientGroup>> CACHE = new java.util.WeakHashMap<>();

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
            List<IngredientGroup> result = doAnalyze(machine);
            CACHE.put(machine, result);
            return result;
        }
    }

    @Nonnull
    private static List<IngredientGroup> doAnalyze(@Nonnull DynamicMachine machine) {
        if (isBlacklisted(machine)) {
            return new ArrayList<>();
        }

        // 候选集合签名 → 聚合中的原料组
        Map<String, GroupBuilder> groups = new LinkedHashMap<>();

        // 控制器本身不在结构 pattern 中，单独计 1 份
        ItemStack controller = controllerStack();
        if (controller != null) {
            groups.put(signatureOf(singletonVariant(controller)),
                    new GroupBuilder(new ArrayList<>(java.util.Collections.singletonList(controller)), 1));
        }

        TaggedPositionBlockArray pattern = machine.getPattern();
        if (pattern == null) {
            return finish(groups);
        }

        for (BlockArray.BlockInformation info : pattern.getPattern().values()) {
            if (info == null || info.getMatchingStates() == null) {
                continue;
            }
            // 展开该位置的全部候选方块，剔除仓室方块
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
                // 纯仓室位置：跳过
                continue;
            }
            String signature = signatureOf(candidates.keySet());
            GroupBuilder builder = groups.get(signature);
            if (builder == null) {
                groups.put(signature, new GroupBuilder(new ArrayList<>(candidates.values()), 1));
            } else {
                builder.count++;
            }
        }

        List<IngredientGroup> result = finish(groups);
        if (MMCEAdditionConfig.debugVirtualParallel) {
            MMCEAddition.LOGGER.info("虚拟装配: 机器 {} 材料清单 {} 组", machine.getRegistryName(), result.size());
        }
        return result;
    }

    @Nonnull
    private static List<IngredientGroup> finish(Map<String, GroupBuilder> groups) {
        List<IngredientGroup> result = new ArrayList<>(groups.size());
        for (GroupBuilder builder : groups.values()) {
            result.add(new IngredientGroup(builder.candidates, builder.count));
        }
        return java.util.Collections.unmodifiableList(result);
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
    private static java.util.Set<ItemVariant> singletonVariant(@Nonnull ItemStack stack) {
        java.util.Set<ItemVariant> set = new java.util.HashSet<>();
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
        java.util.Collections.sort(keys);
        return String.join("|", keys);
    }
}

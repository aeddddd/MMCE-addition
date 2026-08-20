package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import hellfirepvp.modularmachinery.common.util.IBlockStateDescriptor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin：让本模组的方块可以替换原 MMCE 的各类输入/输出仓/总线结构位置。
 * <p>
 * 背景：MMCE 的机器结构匹配逻辑在 {@link BlockArray.BlockInformation#matchesState} 中。
 * 它会检查某个位置的实际方块是否在该位置允许的方块状态列表（matchingStates）中。
 * <p>
 * 这个 Mixin 在 matchesState 方法开头注入：
 * 如果该位置原本期望的是 MMCE 原版的物品/流体输入输出仓室，
 * 而实际放置的是本模组的异步版本或 ME 样板总成，并且配置中开启了兼容，
 * 则直接返回匹配成功，无需修改现有机器 JSON。
 */
@Mixin(value = BlockArray.BlockInformation.class, remap = false)
public class BlockInformationMixin {

    private static final String ASYNC_ITEM_OUTPUT_BUS = "mmceaddition:me_async_item_output_bus";
    private static final String ASYNC_FLUID_OUTPUT_BUS = "mmceaddition:me_async_fluid_output_hatch";
    private static final String ME_PATTERN_ASSEMBLY = "mmceaddition:me_pattern_assembly";
    private static final String ME_OUTPUT_ASSEMBLY = "mmceaddition:me_output_assembly";
    private static final String INPUT_ASSEMBLY = "mmceaddition:input_assembly";
    private static final String VIRTUAL_PARALLEL_HATCH = "mmceaddition:virtual_parallel_hatch";

    /**
     * BlockInformation 中该位置允许的所有方块状态描述符。
     * <p>
     * 通过 @Shadow 让 Mixin 在运行时能够访问目标类的私有字段。
     */
    @Shadow(remap = false)
    private List<IBlockStateDescriptor> matchingStates;

    /**
     * 注入到 matchesState 方法头部。
     * <p>
     * - method = "matchesState"：目标方法名
     * - at = @At("HEAD")：在方法第一行执行
     * - cancellable = true：可以调用 cir.setReturnValue(...) 提前返回，不再执行原方法
     * - remap = false：目标类没有被 obfuscation 重命名，不需要重映射
     *
     * @param world 世界实例
     * @param pos   检查位置
     * @param state 实际方块状态
     * @param cir   回调对象，用于取消并设置返回值
     */
    @Inject(method = "matchesState", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMatchesState(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state == null || matchingStates == null || matchingStates.isEmpty()) {
            return;
        }

        Block actualBlock = state.getBlock();
        String actualRegName = actualBlock.getRegistryName() != null ? actualBlock.getRegistryName().toString() : null;

        // 遍历期望的方块状态，判断该位置是否期望原版物品/流体输入输出仓室或 ME 样板供应器。
        boolean expectItemBus = false;
        boolean expectFluidHatch = false;
        boolean expectPatternProvider = false;
        boolean expectEnergyInputHatch = false;
        boolean expectEnergyOutputHatch = false;

        for (IBlockStateDescriptor descriptor : matchingStates) {
            if (descriptor == null || descriptor.getApplicable() == null) {
                continue;
            }
            for (IBlockState applicable : descriptor.getApplicable()) {
                if (applicable == null) {
                    continue;
                }
                Block block = applicable.getBlock();
                String regName = block.getRegistryName() != null ? block.getRegistryName().toString() : null;
                if (isItemBus(regName)) {
                    expectItemBus = true;
                } else if (isFluidHatch(regName)) {
                    expectFluidHatch = true;
                } else if (isPatternProvider(regName)) {
                    expectPatternProvider = true;
                } else if (isEnergyInputHatch(regName)) {
                    expectEnergyInputHatch = true;
                } else if (isEnergyOutputHatch(regName)) {
                    expectEnergyOutputHatch = true;
                }
            }
        }

        // 异步物品输出总线：可替换任何物品总线位置。
        if (expectItemBus && MMCEAdditionConfig.enableMEItemBusCompat
                && ASYNC_ITEM_OUTPUT_BUS.equals(actualRegName)) {
            cir.setReturnValue(true);
        }
        // 异步流体输出仓：可替换任何流体仓位置。
        else if (expectFluidHatch && MMCEAdditionConfig.enableMEFluidBusCompat
                && ASYNC_FLUID_OUTPUT_BUS.equals(actualRegName)) {
            cir.setReturnValue(true);
        }
        // ME 样板总成：可替换任何物品/流体输入输出仓室、能源输入仓以及原版 ME Pattern Provider 位置。
        else if (MMCEAdditionConfig.enableMEPatternAssemblyCompat
                && ME_PATTERN_ASSEMBLY.equals(actualRegName)
                && (expectItemBus || expectFluidHatch || expectPatternProvider || expectEnergyInputHatch)) {
            cir.setReturnValue(true);
        }
        // ME 输出总成仓：可替换任意物品/流体/能源仓室位置（含输入与输出）。
        // 注意：该方块不提供物品/流体输入功能，替换机器唯一的输入仓会导致断料（Tooltip 已警示）。
        else if (MMCEAdditionConfig.enableMEOutputAssemblyCompat
                && ME_OUTPUT_ASSEMBLY.equals(actualRegName)
                && (expectItemBus || expectFluidHatch
                || expectEnergyInputHatch || expectEnergyOutputHatch)) {
            cir.setReturnValue(true);
        }
        // 输入总成仓：可替换任意物品/流体仓室位置（含输入与输出）。
        // 注意：该方块只提供输入功能，放在输出位置时结构可成型但配方找不到输出组件。
        else if (MMCEAdditionConfig.enableInputAssemblyCompat
                && INPUT_ASSEMBLY.equals(actualRegName)
                && (expectItemBus || expectFluidHatch)) {
            cir.setReturnValue(true);
        }
        // 虚拟并行仓：可替换全部仓室位置（物品/流体/能源输入输出、ME Pattern Provider）。
        // 注意：替换机器唯一的输入仓会导致断料，属玩家自主选择（Tooltip 已警示）。
        else if (MMCEAdditionConfig.enableVirtualHatchCompat
                && VIRTUAL_PARALLEL_HATCH.equals(actualRegName)
                && (expectItemBus || expectFluidHatch || expectPatternProvider
                || expectEnergyInputHatch || expectEnergyOutputHatch)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 判断注册名是否是 MMCE 原版的物品总线（输入或输出）。
     */
    private boolean isItemBus(String regName) {
        return isItemInputBus(regName) || isItemOutputBus(regName);
    }

    /**
     * 判断注册名是否是 MMCE 原版的物品输入总线。
     */
    private boolean isItemInputBus(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmeiteminputbus")
                || regName.equals("modularmachinery:blockinputbus")
                || regName.startsWith("modularmachinery:blockinputbus_");
    }

    /**
     * 判断注册名是否是 MMCE 原版的物品输出总线。
     */
    private boolean isItemOutputBus(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmeitemoutputbus")
                || regName.equals("modularmachinery:blockoutputbus")
                || regName.startsWith("modularmachinery:blockoutputbus_");
    }

    /**
     * 判断注册名是否是 MMCE 原版的流体仓（输入或输出）。
     */
    private boolean isFluidHatch(String regName) {
        return isFluidInputHatch(regName) || isFluidOutputHatch(regName);
    }

    /**
     * 判断注册名是否是 MMCE 原版的流体输入仓。
     */
    private boolean isFluidInputHatch(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmefluidinputbus")
                || regName.equals("modularmachinery:blockfluidinputhatch")
                || regName.startsWith("modularmachinery:blockfluidinputhatch_");
    }

    /**
     * 判断注册名是否是 MMCE 原版的流体输出仓。
     */
    private boolean isFluidOutputHatch(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmefluidoutputbus")
                || regName.equals("modularmachinery:blockfluidoutputhatch")
                || regName.startsWith("modularmachinery:blockfluidoutputhatch_");
    }

    /**
     * 判断注册名是否是 MMCE 原版的 ME 样板供应器。
     */
    private boolean isPatternProvider(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmepatternprovider");
    }

    /**
     * 判断注册名是否是 MMCE 原版的能源输入仓。
     * <p>
     * MMCE-CE 的能源输入仓为单方块多 meta（tiny ~ ultimate），注册名为
     * {@code modularmachinery:blockenergyinputhatch}；startsWith 兜底可能的扩展命名。
     */
    private boolean isEnergyInputHatch(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockenergyinputhatch")
                || regName.startsWith("modularmachinery:blockenergyinputhatch_");
    }

    /**
     * 判断注册名是否是 MMCE 原版的能源输出仓。
     */
    private boolean isEnergyOutputHatch(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockenergyoutputhatch")
                || regName.startsWith("modularmachinery:blockenergyoutputhatch_");
    }
}

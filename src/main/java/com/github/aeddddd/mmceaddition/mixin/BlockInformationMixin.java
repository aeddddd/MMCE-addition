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
 * Mixin：让异步 ME 输出总成可以替换原 MMCE 的各类输出仓/总线结构位置。
 * <p>
 * 背景：MMCE 的机器结构匹配逻辑在 {@link BlockArray.BlockInformation#matchesState} 中。
 * 它会检查某个位置的实际方块是否在该位置允许的方块状态列表（matchingStates）中。
 * <p>
 * 这个 Mixin 在 matchesState 方法开头注入：
 * 如果该位置原本期望的是 MMCE 原版的物品/流体输出仓，
 * 而实际放置的是本模组的异步版本，并且配置中开启了兼容，
 * 则直接返回匹配成功，无需修改现有机器 JSON。
 */
@Mixin(value = BlockArray.BlockInformation.class, remap = false)
public class BlockInformationMixin {

    private static final String ASYNC_ITEM_OUTPUT_BUS = "mmceaddition:me_async_item_output_bus";
    private static final String ASYNC_FLUID_OUTPUT_BUS = "mmceaddition:me_async_fluid_output_hatch";

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
     * @param world  世界实例
     * @param pos    检查位置
     * @param state  实际方块状态
     * @param cir    回调对象，用于取消并设置返回值
     */
    @Inject(method = "matchesState", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMatchesState(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state == null || matchingStates == null || matchingStates.isEmpty()) {
            return;
        }

        Block actualBlock = state.getBlock();
        String actualRegName = actualBlock.getRegistryName() != null ? actualBlock.getRegistryName().toString() : null;

        // 遍历期望的方块状态，判断该位置是否期望原版物品输出总线或流体输出仓。
        boolean expectItemOutput = false;
        boolean expectFluidOutput = false;

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
                if (isItemOutputBus(regName)) {
                    expectItemOutput = true;
                } else if (isFluidOutputHatch(regName)) {
                    expectFluidOutput = true;
                }
            }
        }

        // 如果期望物品输出总线，且配置开启兼容，且实际方块是本模组的异步物品总线，则视为匹配。
        if (expectItemOutput && MMCEAdditionConfig.enableMEItemBusCompat
                && ASYNC_ITEM_OUTPUT_BUS.equals(actualRegName)) {
            cir.setReturnValue(true);
        }
        // 流体同理。
        else if (expectFluidOutput && MMCEAdditionConfig.enableMEFluidBusCompat
                && ASYNC_FLUID_OUTPUT_BUS.equals(actualRegName)) {
            cir.setReturnValue(true);
        }
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
}

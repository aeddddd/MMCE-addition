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
 * 让异步 ME 输出总成可以替换原 MMCE 的各类输出仓/总线结构位置，
 * 无需修改现有机器 JSON。
 */
@Mixin(value = BlockArray.BlockInformation.class, remap = false)
public class BlockInformationMixin {

    private static final String ASYNC_ITEM_OUTPUT_BUS = "mmceaddition:me_async_item_output_bus";
    private static final String ASYNC_FLUID_OUTPUT_BUS = "mmceaddition:me_async_fluid_output_hatch";

    @Shadow(remap = false)
    private List<IBlockStateDescriptor> matchingStates;

    @Inject(method = "matchesState", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMatchesState(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state == null || matchingStates == null || matchingStates.isEmpty()) {
            return;
        }

        Block actualBlock = state.getBlock();
        String actualRegName = actualBlock.getRegistryName() != null ? actualBlock.getRegistryName().toString() : null;

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

        if (expectItemOutput && MMCEAdditionConfig.enableMEItemBusCompat
                && ASYNC_ITEM_OUTPUT_BUS.equals(actualRegName)) {
            cir.setReturnValue(true);
        } else if (expectFluidOutput && MMCEAdditionConfig.enableMEFluidBusCompat
                && ASYNC_FLUID_OUTPUT_BUS.equals(actualRegName)) {
            cir.setReturnValue(true);
        }
    }

    private boolean isItemOutputBus(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmeitemoutputbus")
                || regName.equals("modularmachinery:blockoutputbus")
                || regName.startsWith("modularmachinery:blockoutputbus_");
    }

    private boolean isFluidOutputHatch(String regName) {
        if (regName == null) {
            return false;
        }
        return regName.equals("modularmachinery:blockmefluidoutputbus")
                || regName.equals("modularmachinery:blockfluidoutputhatch")
                || regName.startsWith("modularmachinery:blockfluidoutputhatch_");
    }
}

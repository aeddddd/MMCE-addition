package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.parallel.FakeParallelMigrator;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.MachineRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Mixin：在 MMCE 机器注册（含 /mm reload 重注册）完成后，
 * 触发伪并行迁移扫描（{@link FakeParallelMigrator}）。
 * <p>
 * 迁移是幂等的：迁移器内部按机器对象身份去重，
 * reload 产生的新机器对象会被重新扫描并覆盖旧的授权表。
 */
@Mixin(value = MachineRegistry.class, remap = false)
public class MachineRegistryMixin {

    @Inject(method = "registerMachines", at = @At("RETURN"), remap = false)
    private static void mmceaddition$migrateFakeParallel(Collection<DynamicMachine> machines, CallbackInfo ci) {
        if (MMCEAdditionConfig.enableFakeParallelMigration) {
            FakeParallelMigrator.migrateMachines(machines);
        }
    }

    @Inject(method = "reloadMachine", at = @At("RETURN"), remap = false)
    private static void mmceaddition$migrateFakeParallelOnReload(Collection<DynamicMachine> machines, CallbackInfo ci) {
        if (MMCEAdditionConfig.enableFakeParallelMigration) {
            FakeParallelMigrator.migrateMachines(machines);
        }
    }
}

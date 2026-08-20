package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.parallel.FakeParallelMigrator;
import com.github.aeddddd.mmceaddition.virtual.VirtualParallelManager;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.MachineRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Collections;

/**
 * Mixin：在 MMCE 机器注册（含 /mm reload 重注册）完成后，
 * 触发伪并行迁移扫描（{@link FakeParallelMigrator}）与虚拟并行初始化。
 * <p>
 * 迁移是幂等的：迁移器内部按机器对象身份去重，
 * reload 产生的新机器对象会被重新扫描并覆盖旧的授权表。
 * <p>
 * 注：registerMachines/reloadMachine 在 2.2.2 与 2.3.x 的签名一致（均为 Collection），
 * 无需版本适配。
 */
@Mixin(value = MachineRegistry.class, remap = false)
public class MachineRegistryMixin {

    @Inject(method = "registerMachines", at = @At("RETURN"), remap = false)
    private static void mmceaddition$onRegister(Collection<DynamicMachine> machines, CallbackInfo ci) {
        mmceaddition$afterRegister(machines);
    }

    @Inject(method = "reloadMachine", at = @At("RETURN"), remap = false)
    private static void mmceaddition$onReload(Collection<DynamicMachine> machines, CallbackInfo ci) {
        mmceaddition$afterRegister(machines);
    }

    private static void mmceaddition$afterRegister(Collection<DynamicMachine> machines) {
        if (machines == null) {
            machines = Collections.emptyList();
        }
        if (MMCEAdditionConfig.enableFakeParallelMigration) {
            FakeParallelMigrator.migrateMachines(machines);
        }
        // 虚拟并行：为所有非黑名单机器打开原生并行开关并抬高机器级上限兜底
        VirtualParallelManager.onMachinesRegistered(machines);
    }
}

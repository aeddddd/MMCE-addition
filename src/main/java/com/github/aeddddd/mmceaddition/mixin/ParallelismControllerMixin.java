package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.parallel.FakeParallelMigrator;
import com.github.aeddddd.mmceaddition.parallel.IMigratedParallelismHolder;
import com.github.aeddddd.mmceaddition.virtual.MachineMaterialAnalyzer;
import com.github.aeddddd.mmceaddition.virtual.VirtualParallelManager;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin：把伪并行迁移得到的动态并行度应用到机器控制器上。
 * <p>
 * MMCE 的配方搜索线程会调用 {@code getMaxParallelism()} 并把结果写入
 * {@code ActiveMachineRecipe}，因此在 RETURN 处覆写即可让整个原生并行管线
 * （库存感知的 1..N 动态并行、per-tick 能量缩放）生效。
 * <p>
 * 并行度缓存在控制器实例上（{@link IMigratedParallelismHolder}），
 * 由结构成型/更新事件写入；首次查询时惰性计算（只读内存数据，线程安全）。
 */
@Mixin(value = TileMultiblockMachineController.class, remap = false)
public abstract class ParallelismControllerMixin implements IMigratedParallelismHolder {

    @Shadow(remap = false)
    protected DynamicMachine foundMachine;

    /** 迁移并行度缓存：-1 = 未初始化，1 = 无并行，>1 = 有效并行度。 */
    @Unique
    private volatile int mmceaddition$migratedParallelism = -1;

    @Override
    public int mmceaddition$getMigratedParallelism() {
        return this.mmceaddition$migratedParallelism;
    }

    @Override
    public void mmceaddition$setMigratedParallelism(int value) {
        this.mmceaddition$migratedParallelism = value;
    }

    @Inject(method = "getMaxParallelism()I", at = @At("RETURN"), cancellable = true, remap = false)
    private void mmceaddition$applyMigratedParallelism(CallbackInfoReturnable<Integer> cir) {
        DynamicMachine machine = this.foundMachine;
        if (machine == null) {
            return;
        }

        int value = cir.getReturnValue();

        // ---- 伪并行迁移乘区 ----
        if (FakeParallelMigrator.hasGrants(machine)) {
            // MMCE 先注册机器、后加载配方，迁移时还拿不到配方对象，
            // 因此配方级并行开关（MachineRecipe#parallelized）在这里懒强制。
            FakeParallelMigrator.ensureRecipesParallelized(machine);

            int migrated = this.mmceaddition$migratedParallelism;
            if (migrated < 0) {
                // 惰性计算：computeForController 只读 foundModifiers（ConcurrentHashMap），异步线程安全
                migrated = FakeParallelMigrator.computeForController((TileMultiblockMachineController) (Object) this);
                this.mmceaddition$migratedParallelism = migrated;
            }
            if (migrated > 1) {
                int cap = machine.getMaxParallelism();
                int migratedValue = cap > 0 ? Math.min(migrated, cap) : migrated;
                if (migratedValue > value) {
                    value = migratedValue;
                }
            }
        }

        // ---- 虚拟并行乘区：最终并行 = 其他并行 × (1 + N)，N 为匹配机器数据数量之和 ----
        if (MMCEAdditionConfig.enableVirtualParallel && !MachineMaterialAnalyzer.isBlacklisted(machine)) {
            VirtualParallelManager.ensureRecipesParallelized(machine);
            int factor = VirtualParallelManager.computeVirtualFactor((TileMultiblockMachineController) (Object) this);
            if (factor > 0) {
                // long 累乘防溢出，最终钳制到配置的全局上限
                long multiplied = (long) value * (1L + factor);
                int virtualCap = Math.max(1, MMCEAdditionConfig.virtualParallelCap);
                int virtualValue = (int) Math.min(multiplied, virtualCap);
                if (virtualValue > value) {
                    value = virtualValue;
                }
            }
        }

        if (value > cir.getReturnValue()) {
            cir.setReturnValue(value);
        }
    }
}

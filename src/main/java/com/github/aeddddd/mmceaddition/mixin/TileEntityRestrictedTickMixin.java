package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import hellfirepvp.modularmachinery.common.tiles.base.TileEntityRestrictedTick;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：放开 MMCE “限制 tick” 门控，允许外部 tick 加速器加速机器。
 * <p>
 * MMCE 的 {@code TileEntityRestrictedTick#update} 以世界总 tick 计数去重：
 * 同一 world tick 内的重复 {@code update()} 调用会被直接丢弃。原版 tick 管线
 * 每 tick 只调用一次，而 Quantum Things 时间之瓶（EntityTimeAccelerator）等
 * 加速器正是通过在同一 tick 内额外调用 {@code ITickable.update()} 来加速
 * TileEntity，因此会被该门控完全挡掉，永远无法加速 MMCE 机器。
 * <p>
 * 本 Mixin 在 {@code update} 头部检测“本 tick 已正常执行过”的重复调用，
 * 按 {@code tick_acceleration_mode} 配置决定是否手动执行一次
 * {@code doRestrictedTick()} 并推进 {@code ticksExisted}：
 * time_bottle_only（默认）要求目标位置存在 Quantum Things 的时间加速实体；
 * all 放开所有同原理加速器；off 恢复 MMCE 原版限制。
 */
@Mixin(value = TileEntityRestrictedTick.class, remap = false)
public abstract class TileEntityRestrictedTickMixin {

    /**
     * Quantum Things / Random Things 时间之瓶的加速实体类名。
     * 软引用：未安装时不触发类加载，兼容模式自动退化为不加速。
     */
    private static final String TIME_ACCELERATOR_CLASS_NAME = "lumien.randomthings.entitys.EntityTimeAccelerator";

    private static Class<?> timeAcceleratorClass;
    private static boolean timeAcceleratorSearched = false;

    /** 每个 TE 实例的检测结果缓存：一个 world tick 内最多做一次实体扫描。 */
    private long mmceaddition$lastAccelCheckTick = -1;
    private boolean mmceaddition$lastAccelCheckResult = false;

    @Shadow
    protected int ticksExisted;

    @Shadow
    private long lastUpdateWorldTick;

    @Shadow
    public abstract void doRestrictedTick();

    @Inject(method = {"func_73660_a", "update"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void mmceaddition$allowAcceleratedTick(CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || this.lastUpdateWorldTick != world.getTotalWorldTime()) {
            return;
        }
        // 本 tick 已被原版 tick 管线执行过一次 → 这是外部加速器的额外调用
        String mode = MMCEAdditionConfig.tickAccelerationMode;
        boolean allow;
        if ("all".equalsIgnoreCase(mode)) {
            allow = true;
        } else if ("off".equalsIgnoreCase(mode)) {
            allow = false;
        } else {
            // time_bottle_only（默认及未知值）：仅时间之瓶，结果按 tick 缓存
            long totalTime = world.getTotalWorldTime();
            if (this.mmceaddition$lastAccelCheckTick != totalTime) {
                this.mmceaddition$lastAccelCheckTick = totalTime;
                this.mmceaddition$lastAccelCheckResult = mmceaddition$hasTimeAccelerator(world, self.getPos());
            }
            allow = this.mmceaddition$lastAccelCheckResult;
        }
        if (!allow) {
            return;
        }
        this.doRestrictedTick();
        this.ticksExisted++;
        ci.cancel();
    }

    /**
     * 检测目标位置是否存在 Quantum Things 的时间加速实体。
     * 检测范围与时间之瓶自身的判定一致（方块 AABB 收缩 0.2）。
     */
    private static boolean mmceaddition$hasTimeAccelerator(World world, BlockPos pos) {
        if (!timeAcceleratorSearched) {
            timeAcceleratorSearched = true;
            try {
                timeAcceleratorClass = Class.forName(TIME_ACCELERATOR_CLASS_NAME);
            } catch (Throwable ignored) {
                // 未安装 Quantum Things / Random Things
            }
        }
        if (timeAcceleratorClass == null) {
            return false;
        }
        AxisAlignedBB box = new AxisAlignedBB(pos).shrink(0.2);
        for (Entity entity : world.getEntitiesWithinAABB(Entity.class, box)) {
            if (timeAcceleratorClass.isInstance(entity)) {
                return true;
            }
        }
        return false;
    }
}

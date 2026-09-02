package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import hellfirepvp.modularmachinery.common.tiles.base.TileItemBus;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：为 MMCE 物品总线（输入/输出）附加“自动 IO 开关”状态，并随 NBT 持久化。
 * <p>
 * 默认开启；关闭后由子类 Mixin 在 {@code doRestrictedTick} 头部直接取消自动抽取/推送。
 */
@Mixin(value = TileItemBus.class, remap = false)
public abstract class TileItemBusMixin implements IAutoIOControllable {

    /** 自动 IO 开关，默认开启。 */
    @Unique
    private boolean mmceaddition$autoIOEnabled = true;

    @Override
    public boolean mmceaddition$isAutoIOEnabled() {
        return this.mmceaddition$autoIOEnabled;
    }

    @Override
    public void mmceaddition$setAutoIOEnabled(boolean enabled) {
        this.mmceaddition$autoIOEnabled = enabled;
    }

    @Inject(method = "readCustomNBT", at = @At("RETURN"), remap = false)
    private void mmceaddition$readAutoIO(NBTTagCompound compound, CallbackInfo ci) {
        if (compound.hasKey("mmceAutoIO")) {
            this.mmceaddition$autoIOEnabled = compound.getBoolean("mmceAutoIO");
        }
    }

    @Inject(method = "writeCustomNBT", at = @At("RETURN"), remap = false)
    private void mmceaddition$writeAutoIO(NBTTagCompound compound, CallbackInfo ci) {
        compound.setBoolean("mmceAutoIO", this.mmceaddition$autoIOEnabled);
    }
}

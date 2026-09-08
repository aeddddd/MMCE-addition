package com.github.aeddddd.mmceaddition.mixin;

import com.github.aeddddd.mmceaddition.util.IFailureDetailsHolder;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mixin：在 MMCE 机器控制器上挂载“配方失败详情”缓存，并随 NBT 同步到客户端。
 * <p>
 * MMCE 原生的 {@code CraftingStatus} 只保留最佳匹配配方的第一条错误消息，
 * GUI/TOP 只能显示“缺少物品输入！”这类单行提示。本 Mixin 缓存完整错误列表，
 * 由搜索/完成检查失败时写入（见 {@code RecipeSearchTaskMixin} 等）。
 * <p>
 * 同步：MMCE 控制器经 {@code writeCustomNBT/readCustomNBT} 向客户端同步状态
 * （含 CraftingStatus），失败详情挂到同一通道，控制器 GUI 才能拿到完整列表。
 */
@Mixin(value = TileMultiblockMachineController.class, remap = false)
public abstract class FailureDetailsControllerMixin implements IFailureDetailsHolder {

    @Unique
    private static final String MMCEADDITION$NBT_KEY = "mmceadditionFailureDetails";

    @Unique
    private volatile List<String> mmceaddition$failureDetails = Collections.emptyList();

    @Override
    public List<String> mmceaddition$getFailureDetails() {
        return this.mmceaddition$failureDetails;
    }

    @Override
    public void mmceaddition$setFailureDetails(List<String> details) {
        this.mmceaddition$failureDetails = details == null ? Collections.emptyList() : details;
    }

    @Inject(method = "writeCustomNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), remap = false)
    private void mmceaddition$writeFailureDetails(NBTTagCompound compound, CallbackInfo ci) {
        List<String> details = this.mmceaddition$failureDetails;
        if (details.isEmpty()) {
            compound.removeTag(MMCEADDITION$NBT_KEY);
            return;
        }
        NBTTagList list = new NBTTagList();
        for (String detail : details) {
            list.appendTag(new NBTTagString(detail));
        }
        compound.setTag(MMCEADDITION$NBT_KEY, list);
    }

    @Inject(method = "readCustomNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), remap = false)
    private void mmceaddition$readFailureDetails(NBTTagCompound compound, CallbackInfo ci) {
        if (!compound.hasKey(MMCEADDITION$NBT_KEY, Constants.NBT.TAG_LIST)) {
            this.mmceaddition$failureDetails = Collections.emptyList();
            return;
        }
        NBTTagList list = compound.getTagList(MMCEADDITION$NBT_KEY, Constants.NBT.TAG_STRING);
        List<String> details = new ArrayList<>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            details.add(list.getStringTagAt(i));
        }
        this.mmceaddition$failureDetails = Collections.unmodifiableList(details);
    }
}

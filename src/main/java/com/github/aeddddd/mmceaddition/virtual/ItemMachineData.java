package com.github.aeddddd.mmceaddition.virtual;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.MachineRegistry;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * 机器数据物品。
 * <p>
 * 由虚拟装配台消耗材料产出，NBT 记录：
 * <ul>
 *   <li>{@code machine}：MMCE 机器注册名（如 modularmachinery:xxx）</li>
 *   <li>{@code count}：内部存储的该种多方块机器数量（int 上限）</li>
 * </ul>
 * 放入对应多方块机器的虚拟并行仓后，按 count 提供独立乘区并行度。
 * 不进入创造物品栏，只能通过虚拟装配台获得。
 */
public class ItemMachineData extends Item {

    private static final String TAG_MACHINE = "machine";
    private static final String TAG_COUNT = "count";

    public ItemMachineData() {
        setTranslationKey(MMCEAddition.MODID + ".machine_data");
        setRegistryName(MMCEAddition.MODID, "machine_data");
        // 堆叠语义与内部 count 冲突，固定不可堆叠
        setMaxStackSize(1);
    }

    /**
     * 创建一份机器数据。
     *
     * @param machineName 机器注册名（modid:path）
     * @param count       内部存储的机器数量
     */
    @Nonnull
    public static ItemStack createStack(String machineName, int count) {
        ItemStack stack = new ItemStack(RegistryHandlerHolder.MACHINE_DATA);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(TAG_MACHINE, machineName);
        tag.setInteger(TAG_COUNT, Math.max(1, count));
        stack.setTagCompound(tag);
        return stack;
    }

    /**
     * 读取机器注册名；无数据时返回 null。
     */
    @Nullable
    public static String getMachineName(@Nonnull ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_MACHINE)) {
            return null;
        }
        return tag.getString(TAG_MACHINE);
    }

    /**
     * 读取内部存储的机器数量；无数据时返回 0。
     */
    public static int getCount(@Nonnull ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_COUNT)) {
            return 0;
        }
        return tag.getInteger(TAG_COUNT);
    }

    /**
     * 设置内部存储的机器数量（钳制到 [1, Integer.MAX_VALUE]）。
     */
    public static void setCount(@Nonnull ItemStack stack, int count) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger(TAG_COUNT, Math.max(1, count));
    }

    /**
     * 按机器注册名解析机器对象；未找到时返回 null。
     */
    @Nullable
    public static DynamicMachine resolveMachine(@Nullable String machineName) {
        if (machineName == null) {
            return null;
        }
        return MachineRegistry.getRegistry().getMachine(new ResourceLocation(machineName));
    }

    /**
     * 取机器的显示名（解析失败时退回注册名原文）。
     */
    @Nonnull
    public static String machineDisplayName(@Nullable String machineName) {
        if (machineName == null) {
            return "?";
        }
        DynamicMachine machine = resolveMachine(machineName);
        if (machine != null && machine.getLocalizedName() != null && !machine.getLocalizedName().isEmpty()) {
            return machine.getLocalizedName();
        }
        return machineName;
    }

    @Nonnull
    @Override
    public String getItemStackDisplayName(@Nonnull ItemStack stack) {
        String machineName = getMachineName(stack);
        // 服务端安全的翻译接口（本类不涉及客户端专有类）
        String base = net.minecraft.util.text.translation.I18n.translateToLocal("item.mmceaddition.machine_data.name");
        if (machineName == null) {
            return base;
        }
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(
                "item.mmceaddition.machine_data.named", base, machineDisplayName(machineName));
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(@Nonnull ItemStack stack, @Nullable World world, @Nonnull List<String> tooltip, @Nonnull ITooltipFlag flag) {
        String machineName = getMachineName(stack);
        if (machineName != null) {
            tooltip.add(I18n.format("tooltip.mmceaddition.machine_data.machine", machineDisplayName(machineName)));
            tooltip.add(I18n.format("tooltip.mmceaddition.machine_data.count", getCount(stack)));
        }
        tooltip.add(I18n.format("tooltip.mmceaddition.machine_data.usage"));
    }

    /**
     * 客户端染色：按机器注册名 hash 生成稳定色调，同机器同色、不同机器可区分。
     * 叠加层为白色电路走线贴图，染色乘法生效。
     */
    public static int tintFor(@Nullable String machineName) {
        if (machineName == null) {
            return 0xFFFFFFFF;
        }
        int hash = machineName.hashCode();
        float hue = (hash & 0xFFFF) / 65535.0f;
        // 高饱和高亮度，保证染色后电路走线清晰可辨
        return java.awt.Color.HSBtoRGB(hue, 0.75f, 1.0f) | 0xFF000000;
    }

    /**
     * 延迟初始化持有者：避免 ItemMachineData 类加载早于 RegistryHandler 静态实例创建。
     */
    private static final class RegistryHandlerHolder {
        private static final Item MACHINE_DATA = com.github.aeddddd.mmceaddition.RegistryHandler.MACHINE_DATA;
    }
}

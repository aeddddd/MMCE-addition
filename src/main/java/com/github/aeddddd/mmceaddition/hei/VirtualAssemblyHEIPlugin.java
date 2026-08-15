package com.github.aeddddd.mmceaddition.hei;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.MachineMaterialAnalyzer;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.MachineRegistry;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * HEI/JEI 插件：虚拟装配台的配方列表。
 * <p>
 * 每种 MMCE 机器映射为一条"配方"：输入为结构材料清单
 * （多选一原料组天然适配 JEI 的 {@code List<ItemStack>} 槽位语义，交替显示候选），
 * 输出为对应的机器数据。黑名单机器与无材料清单的机器不生成条目。
 * <p>
 * {@code @JEIPlugin} 类只在 HEI/JEI 存在时被加载，天然软依赖。
 * 注意：条目在插件注册时一次性构建，/mm reload 后需重启客户端才会刷新。
 */
@JEIPlugin
public class VirtualAssemblyHEIPlugin implements IModPlugin {

    public static final String UID = "mmceaddition.virtual_assembly";

    /** 配方布局：输入 9x4，输出 1。 */
    private static final int MAX_INPUT_SLOTS = 36;

    @Override
    public void register(IModRegistry registry) {
        if (!MMCEAdditionConfig.enableVirtualParallel) {
            return;
        }
        IGuiHelper guiHelper = registry.getJeiHelpers().getGuiHelper();
        registry.addRecipeCategories(new VirtualAssemblyCategory(guiHelper));
        registry.addRecipeCategoryCraftingItem(new ItemStack(RegistryHandler.VIRTUAL_ASSEMBLER), UID);

        List<VirtualAssemblyRecipe> recipes = new ArrayList<>();
        for (DynamicMachine machine : MachineRegistry.getLoadedMachines()) {
            if (machine == null || MachineMaterialAnalyzer.isBlacklisted(machine)) {
                continue;
            }
            List<MachineMaterialAnalyzer.IngredientGroup> groups = MachineMaterialAnalyzer.analyze(machine);
            if (groups.isEmpty()) {
                continue;
            }
            recipes.add(new VirtualAssemblyRecipe(machine, groups));
        }
        registry.addRecipes(recipes, UID);
    }

    /**
     * 一条虚拟装配配方：机器 → 材料清单 + 机器数据。
     */
    public static class VirtualAssemblyRecipe implements IRecipeWrapper {

        private final DynamicMachine machine;
        private final List<MachineMaterialAnalyzer.IngredientGroup> groups;

        public VirtualAssemblyRecipe(DynamicMachine machine, List<MachineMaterialAnalyzer.IngredientGroup> groups) {
            this.machine = machine;
            this.groups = groups;
        }

        @Override
        public void getIngredients(@Nonnull IIngredients ingredients) {
            List<List<ItemStack>> inputs = new ArrayList<>();
            int limit = Math.min(groups.size(), MAX_INPUT_SLOTS);
            for (int i = 0; i < limit; i++) {
                MachineMaterialAnalyzer.IngredientGroup group = groups.get(i);
                List<ItemStack> candidates = new ArrayList<>(group.getCandidates().size());
                for (ItemStack candidate : group.getCandidates()) {
                    ItemStack stack = candidate.copy();
                    stack.setCount(group.getCount());
                    candidates.add(stack);
                }
                inputs.add(candidates);
            }
            ingredients.setInputLists(VanillaTypes.ITEM, inputs);
            ingredients.setOutput(VanillaTypes.ITEM,
                    ItemMachineData.createStack(machine.getRegistryName().toString(), 1));
        }

        @Override
        public void drawInfo(@Nonnull Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
            // 左下角显示结构方块总数与截断提示
            long total = 0;
            for (MachineMaterialAnalyzer.IngredientGroup group : groups) {
                total += group.getCount();
            }
            String text = I18n.format("hei.virtual_assembly.total_blocks", total);
            if (groups.size() > MAX_INPUT_SLOTS) {
                text += " " + I18n.format("hei.virtual_assembly.more", groups.size() - MAX_INPUT_SLOTS);
            }
            minecraft.fontRenderer.drawString(text, 1, recipeHeight - 9, 0x606060);
        }
    }

    /**
     * 配方类别：虚拟装配。
     */
    public static class VirtualAssemblyCategory implements IRecipeCategory<VirtualAssemblyRecipe> {

        private static final ResourceLocation TEXTURE =
                new ResourceLocation(MMCEAddition.MODID, "textures/gui/hei_virtual_assembly.png");

        private final IDrawable background;

        public VirtualAssemblyCategory(IGuiHelper guiHelper) {
            this.background = guiHelper.createDrawable(TEXTURE, 0, 0, 170, 110);
        }

        @Nonnull
        @Override
        public String getUid() {
            return UID;
        }

        @Nonnull
        @Override
        public String getTitle() {
            return I18n.format("hei.virtual_assembly.title");
        }

        @Nonnull
        @Override
        public String getModName() {
            return MMCEAddition.NAME;
        }

        @Nonnull
        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public void setRecipe(@Nonnull IRecipeLayout recipeLayout, @Nonnull VirtualAssemblyRecipe recipeWrapper, @Nonnull IIngredients ingredients) {
            IGuiItemStackGroup group = recipeLayout.getItemStacks();
            for (int i = 0; i < MAX_INPUT_SLOTS; i++) {
                group.init(i, true, 1 + (i % 9) * 18, 16 + (i / 9) * 18);
            }
            group.init(MAX_INPUT_SLOTS, false, 147, 88);
            group.set(ingredients);
        }
    }
}

package com.github.aeddddd.mmceaddition.command;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.compat.NetworkEnergyCompat;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.parallel.FakeParallelMigrator;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.virtual.VirtualParallelManager;
import github.kasuminova.mmce.common.tile.MEItemOutputBus;
import hellfirepvp.modularmachinery.common.crafting.helper.ProcessingComponent;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import hellfirepvp.modularmachinery.common.util.IEnergyHandler;
import hellfirepvp.modularmachinery.common.util.IEnergyHandlerAsync;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 调试/管理命令。
 * <p>
 * 用于把已加载区块中的 MMCE 原版 ME 物品输出仓一键替换为本模组的异步版本，
 * 方便在已有存档上测试性能差异。
 */
public class CommandMMCEAddition extends CommandBase {

    @Override
    @Nonnull
    public String getName() {
        return "mmceaddition";
    }

    @Override
    @Nonnull
    public String getUsage(@Nonnull ICommandSender sender) {
        return "/mmceaddition replaceMeItemBus|inspect";
    }

    @Override
    public int getRequiredPermissionLevel() {
        // 需要 OP 权限等级 2（创造模式命令通常用这个等级）。
        return 2;
    }

    @Override
    @Nonnull
    public List<String> getAliases() {
        return Collections.singletonList("mmcea");
    }

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
            return;
        }
        if ("inspect".equalsIgnoreCase(args[0])) {
            executeInspect(sender);
            return;
        }
        if (!"replaceMeItemBus".equalsIgnoreCase(args[0])) {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
            return;
        }

        World world = sender.getEntityWorld();
        int replaced = 0;
        int transferred = 0;

        // 只遍历已加载区块。
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            sender.sendMessage(new TextComponentString("§c该维度不支持区块遍历。"));
            return;
        }
        ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();

        for (Chunk chunk : provider.getLoadedChunks()) {
            // chunk.getTileEntityMap() 返回该区块内所有 TileEntity。
            for (TileEntity te : chunk.getTileEntityMap().values()) {
                if (te instanceof MEItemOutputBus) {
                    BlockPos pos = te.getPos();

                    // 先读取原仓内待输出的物品。
                    IOInventory inv = ((MEItemOutputBus) te).getInternalInventory();
                    ItemStack[] contents = null;
                    if (inv != null) {
                        int slots = inv.getSlots();
                        contents = new ItemStack[slots];
                        for (int i = 0; i < slots; i++) {
                            contents[i] = inv.getStackInSlot(i).copy();
                        }
                    }

                    // 替换方块。setBlockState 会自动移除旧 TileEntity 并创建新的。
                    world.setBlockState(pos, RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS.getDefaultState(), 2);
                    TileEntity newTe = world.getTileEntity(pos);
                    if (newTe instanceof TileMEAsyncItemOutputBus && contents != null) {
                        TileMEAsyncItemOutputBus async = (TileMEAsyncItemOutputBus) newTe;
                        for (ItemStack stack : contents) {
                            if (!stack.isEmpty()) {
                                async.getItemBuffer().insert(stack);
                                transferred += stack.getCount();
                            }
                        }
                    }
                    replaced++;
                }
            }
        }

        sender.sendMessage(new TextComponentTranslation(
                "commands.mmceaddition.replaceMeItemBus.success", replaced, transferred));
    }

    /**
     * 诊断子命令：看向 MMCE 控制器时 dump 其 foundComponents，
     * 用于排查“没有找到能量输入口”这类组件识别问题；
     * 看向 ME 样板总成时显示其网络能源状态。
     */
    private void executeInspect(ICommandSender sender) {
        Entity entity = sender.getCommandSenderEntity();
        if (!(entity instanceof EntityPlayer)) {
            sender.sendMessage(new TextComponentString("§c只有玩家可以使用该命令（需要看向目标方块）。"));
            return;
        }
        EntityPlayer player = (EntityPlayer) entity;
        RayTraceResult ray = player.rayTrace(8.0D, 1.0F);
        if (ray == null || ray.typeOfHit != RayTraceResult.Type.BLOCK) {
            sender.sendMessage(new TextComponentString("§c请看向 MMCE 控制器或 ME 样板总成。"));
            return;
        }
        World world = sender.getEntityWorld();
        TileEntity te = world.getTileEntity(ray.getBlockPos());
        if (te instanceof TileMultiblockMachineController) {
            inspectController(sender, (TileMultiblockMachineController) te);
        } else if (te instanceof TileMEPatternAssembly) {
            inspectPatternAssembly(sender, (TileMEPatternAssembly) te);
        } else {
            sender.sendMessage(new TextComponentString("§c目标不是 MMCE 控制器或 ME 样板总成："
                    + (te == null ? "无 TileEntity" : te.getClass().getName())));
        }
    }

    private void inspectController(ICommandSender sender, TileMultiblockMachineController ctrl) {
        sender.sendMessage(new TextComponentString("§e--- 控制器诊断 ---"));
        sender.sendMessage(new TextComponentString("结构成形: " + ctrl.isStructureFormed()));
        DynamicMachine machine = ctrl.getFoundMachine();
        sender.sendMessage(new TextComponentString("匹配机器: "
                + (machine == null ? "§c无" : String.valueOf(machine.getRegistryName()))));

        // 版本适配：2.2.2 为 Map<TileEntity, ProcessingComponent>，
        // 2.3.x 为按组分桶的 Map<Long, Map<TileEntity, ProcessingComponent>>，统一扁平化
        Map<?, ?> rawComponents = ctrl.getFoundComponents();
        List<Map.Entry<TileEntity, ProcessingComponent<?>>> flat = new ArrayList<>();
        for (Map.Entry<?, ?> entry : rawComponents.entrySet()) {
            if (entry.getKey() instanceof TileEntity && entry.getValue() instanceof ProcessingComponent) {
                @SuppressWarnings("unchecked")
                Map.Entry<TileEntity, ProcessingComponent<?>> e =
                        (Map.Entry<TileEntity, ProcessingComponent<?>>) (Map.Entry<?, ?>) entry;
                flat.add(e);
            } else if (entry.getValue() instanceof Map) {
                for (Map.Entry<?, ?> inner : ((Map<?, ?>) entry.getValue()).entrySet()) {
                    if (inner.getKey() instanceof TileEntity && inner.getValue() instanceof ProcessingComponent) {
                        @SuppressWarnings("unchecked")
                        Map.Entry<TileEntity, ProcessingComponent<?>> e =
                                (Map.Entry<TileEntity, ProcessingComponent<?>>) (Map.Entry<?, ?>) inner;
                        flat.add(e);
                    }
                }
            }
        }
        sender.sendMessage(new TextComponentString("foundComponents 数量: " + flat.size()));
        int validEnergyInput = 0;
        for (Map.Entry<TileEntity, ProcessingComponent<?>> entry : flat) {
            TileEntity componentTe = entry.getKey();
            ProcessingComponent<?> pc = entry.getValue();
            MachineComponent<?> component = pc.component();
            Object provider = pc.getProvidedComponent();

            boolean isEnergyInput = component instanceof MachineComponent.EnergyHatch
                    && component.ioType == IOType.INPUT
                    && provider instanceof IEnergyHandler;
            if (isEnergyInput) {
                validEnergyInput++;
            }
            Object typeName = component.getComponentType() == null
                    ? "null" : component.getComponentType().getRegistryName();
            sender.sendMessage(new TextComponentString(String.format(
                    "%s- %s @ %s | 组件=%s | 类型=%s | IO=%s | 提供者=%s",
                    isEnergyInput ? "§a" : "§7",
                    componentTe.getClass().getSimpleName(),
                    componentTe.getPos().toString().replace("BlockPos", ""),
                    component.getClass().getSimpleName().isEmpty()
                            ? component.getClass().getName() : component.getClass().getSimpleName(),
                    typeName,
                    component.ioType,
                    provider == null ? "null" : provider.getClass().getSimpleName())));
        }
        sender.sendMessage(new TextComponentString(validEnergyInput > 0
                ? "§a有效能源输入组件: " + validEnergyInput
                : "§c未找到有效能源输入组件（RequirementEnergy.isValidComponent 全部不通过）"));

        // 伪并行授权表 + 虚拟并行诊断：确认迁移是否产出授权、虚拟乘区读到多少
        if (machine != null) {
            List<FakeParallelMigrator.Grant> grants = FakeParallelMigrator.grantsFor(machine);
            if (grants.isEmpty()) {
                sender.sendMessage(new TextComponentString("§7伪并行授权表: 空（该机器未检测到伪并行组或未迁移）"));
            } else {
                StringBuilder sb = new StringBuilder("§a伪并行授权表:");
                for (FakeParallelMigrator.Grant grant : grants) {
                    sb.append(" ").append(grant.modifierName).append("=x").append(grant.parallelism).append(',');
                }
                sb.setLength(sb.length() - 1);
                sender.sendMessage(new TextComponentString(sb.toString()));
            }
            sender.sendMessage(new TextComponentString("§7虚拟并行加数 N = "
                    + VirtualParallelManager.computeVirtualFactor(ctrl)
                    + "（最终并行 = 其他并行 x(1+N)，上限 " + MMCEAdditionConfig.virtualParallelCap + "）"));
        }
    }

    private void inspectPatternAssembly(ICommandSender sender, TileMEPatternAssembly assembly) {
        sender.sendMessage(new TextComponentString("§e--- ME 样板总成诊断 ---"));
        ProcessingComponent<IEnergyHandlerAsync> component = assembly.createEnergyInputProcessingComponent();
        MachineComponent<?> machineComponent = component.component();
        sender.sendMessage(new TextComponentString("能源组件: §a"
                + machineComponent.getClass().getSimpleName()
                + " | 类型=" + (machineComponent.getComponentType() == null
                ? "null" : machineComponent.getComponentType().getRegistryName())
                + " | IO=" + machineComponent.ioType));
        sender.sendMessage(new TextComponentString("AE2Enhanced API 可用: " + NetworkEnergyCompat.isAvailable()));
        sender.sendMessage(new TextComponentString("网络 RF 存量: " + assembly.getNetworkEnergy()));
    }

    @Override
    @Nonnull
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
                                           @Nonnull String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "replaceMeItemBus", "inspect");
        }
        return Collections.emptyList();
    }
}

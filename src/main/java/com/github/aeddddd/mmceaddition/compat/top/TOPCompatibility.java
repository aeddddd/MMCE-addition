package com.github.aeddddd.mmceaddition.compat.top;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.tile.TileInputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncFluidOutputHatch;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
import com.github.aeddddd.mmceaddition.tile.TileMEOutputAssembly;
import com.github.aeddddd.mmceaddition.tile.TileMEPatternAssembly;
import com.github.aeddddd.mmceaddition.util.FailureDetails;
import com.github.aeddddd.mmceaddition.util.IAutoIOControllable;
import com.github.aeddddd.mmceaddition.virtual.ItemMachineData;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualAssembler;
import com.github.aeddddd.mmceaddition.virtual.TileVirtualParallelHatch;
import hellfirepvp.modularmachinery.common.crafting.helper.CraftingStatus;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Function;

/**
 * The One Probe（TOP）兼容层。
 * <p>
 * 注册方式：在 init 阶段通过 IMC 发送 {@code getTheOneProbe} 函数消息，
 * TOP 加载后会反射实例化 {@link GetTheOneProbe} 并回调 {@link GetTheOneProbe#apply}，
 * 因此本类只会在 TOP 存在时被加载（软依赖，与 AE2FC 的保护方式一致）。
 * <p>
 * 显示文本使用 TOP 的 {@code {*language.key*}} 占位符语法，由 TOP 在客户端按当前语言翻译，
 * 因此 addProbeInfo 虽然在服务端执行，也能正确显示中文。
 */
public class TOPCompatibility {

    /**
     * IMC 回调入口。TOP 通过反射加载本类，签名必须是 {@code Function<ITheOneProbe, ?>}。
     */
    public static class GetTheOneProbe implements Function<ITheOneProbe, Void> {
        @Override
        public Void apply(ITheOneProbe probe) {
            probe.registerProvider(new HatchProbeProvider());
            return null;
        }
    }

    /**
     * 为本模组的仓室/总成方块提供状态信息：网络连接、缓冲内容、运行情况。
     */
    public static class HatchProbeProvider implements IProbeInfoProvider {

        @Override
        public String getID() {
            return MMCEAddition.MODID + ":hatch_provider";
        }

        @Override
        public void addProbeInfo(ProbeMode mode, IProbeInfo info, EntityPlayer player, World world,
                                 IBlockState state, IProbeHitData data) {
            TileEntity te = world.getTileEntity(data.getPos());
            // MMCE 机器控制器：配方失败时展示完整错误列表（MMCE 原生只显示第一条）
            if (te instanceof TileMultiblockMachineController) {
                addControllerFailureDetails((TileMultiblockMachineController) te, info);
            }
            // 自动 IO 开关状态（MMCE 物品总线/能源仓，由 Mixin 混入 IAutoIOControllable）
            if (te instanceof IAutoIOControllable) {
                boolean auto = ((IAutoIOControllable) te).mmceaddition$isAutoIOEnabled();
                text(info, "{*top.mmceaddition.auto_io*}: "
                        + (auto ? "{*top.mmceaddition.auto_io_on*}" : "{*top.mmceaddition.auto_io_off*}"));
            }
            if (te instanceof TileMEAsyncItemOutputBus) {
                TileMEAsyncItemOutputBus bus = (TileMEAsyncItemOutputBus) te;
                addNetworkLine(info, bus.getMainNode().isActive());
                long total = bus.getBufferedAmount();
                if (total > 0) {
                    addBufferLine(info, "top.mmceaddition.buffered_items",
                            bus.getItemBuffer().snapshot().size(), total, "top.mmceaddition.unit_items");
                }
            } else if (te instanceof TileMEAsyncFluidOutputHatch) {
                TileMEAsyncFluidOutputHatch hatch = (TileMEAsyncFluidOutputHatch) te;
                addNetworkLine(info, hatch.getMainNode().isActive());
                long total = hatch.getBufferedAmount();
                if (total > 0) {
                    addBufferLine(info, "top.mmceaddition.buffered_fluids",
                            hatch.getFluidBuffer().snapshot().size(), total, "top.mmceaddition.unit_mb");
                }
            } else if (te instanceof TileMEPatternAssembly) {
                TileMEPatternAssembly assembly = (TileMEPatternAssembly) te;
                addNetworkLine(info, assembly.isNetworkActive());
                text(info, "{*top.mmceaddition.pattern_slots*}: "
                        + assembly.getUsedPatternSlotCount() + " / " + TileMEPatternAssembly.PATTERNS);
                long inItems = assembly.getTotalBufferedInputItems();
                long inFluids = assembly.getTotalBufferedInputFluids();
                if (inItems > 0 || inFluids > 0) {
                    text(info, "{*top.mmceaddition.input_buffer*}: " + itemsAndFluids(inItems, inFluids));
                }
                long outItems = assembly.getTotalBufferedOutputItems();
                long outFluids = assembly.getTotalBufferedOutputFluids();
                if (outItems > 0 || outFluids > 0) {
                    text(info, "{*top.mmceaddition.output_buffer*}: " + itemsAndFluids(outItems, outFluids));
                }
            } else if (te instanceof TileMEOutputAssembly) {
                TileMEOutputAssembly assembly = (TileMEOutputAssembly) te;
                addNetworkLine(info, assembly.getMainNode().isActive());
                long items = assembly.getBufferedItemAmount();
                if (items > 0) {
                    addBufferLine(info, "top.mmceaddition.buffered_items",
                            assembly.getItemBuffer().snapshot().size(), items, "top.mmceaddition.unit_items");
                }
                long fluids = assembly.getBufferedFluidAmount();
                if (fluids > 0) {
                    addBufferLine(info, "top.mmceaddition.buffered_fluids",
                            assembly.getFluidBuffer().snapshot().size(), fluids, "top.mmceaddition.unit_mb");
                }
                long energy = assembly.getNetworkEnergy();
                if (energy > 0) {
                    text(info, "{*top.mmceaddition.network_energy*}: " + fmt(energy) + " RF");
                }
            } else if (te instanceof TileInputAssembly) {
                TileInputAssembly assembly = (TileInputAssembly) te;
                long items = assembly.getBufferedItemAmount();
                if (items > 0) {
                    addBufferLine(info, "top.mmceaddition.buffered_items",
                            assembly.getItemBuffer().snapshot().size(), items, "top.mmceaddition.unit_items");
                }
                long fluids = assembly.getBufferedFluidAmount();
                if (fluids > 0) {
                    addBufferLine(info, "top.mmceaddition.buffered_fluids",
                            assembly.getFluidBuffer().snapshot().size(), fluids, "top.mmceaddition.unit_mb");
                }
            } else if (te instanceof TileVirtualAssembler) {
                TileVirtualAssembler assembler = (TileVirtualAssembler) te;
                int kinds = 0;
                long total = 0;
                TileVirtualAssembler.AssemblerBufferHandler buffer = assembler.getBuffer();
                for (int i = 0; i < buffer.getSlots(); i++) {
                    ItemStack stack = buffer.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        kinds++;
                        total += stack.getCount();
                    }
                }
                if (total > 0) {
                    addBufferLine(info, "top.mmceaddition.materials", kinds, total, "top.mmceaddition.unit_items");
                }
                ItemStack output = assembler.getOutput().getStack();
                if (!output.isEmpty()) {
                    text(info, "{*top.mmceaddition.output_slot*}: "
                            + output.getDisplayName() + " x" + output.getCount());
                }
            } else if (te instanceof TileVirtualParallelHatch) {
                TileVirtualParallelHatch hatch = (TileVirtualParallelHatch) te;
                ItemStack dataStack = hatch.getDataStack();
                if (dataStack.isEmpty()) {
                    text(info, "{*top.mmceaddition.machine_data*}: {*top.mmceaddition.empty*}");
                } else {
                    text(info, "{*top.mmceaddition.machine_data*}: "
                            + ItemMachineData.machineDisplayName(ItemMachineData.getMachineName(dataStack))
                            + " x" + fmt(ItemMachineData.getCount(dataStack)));
                    if (hatch.getController() == null) {
                        text(info, "{*top.mmceaddition.status*}: {*top.mmceaddition.no_machine*}");
                    } else if (hatch.isDataMatched()) {
                        text(info, "{*top.mmceaddition.status*}: {*top.mmceaddition.active*}");
                    } else {
                        text(info, "{*top.mmceaddition.status*}: {*top.mmceaddition.mismatched*}");
                    }
                }
            }
        }

        /**
         * 控制器失败详情：状态非工作中、状态消息仍在缓存列表内（防止过期）、
         * 且除第一条错误（MMCE 探针已显示）外还有其他错误时，逐行输出。
         * 错误条目经 {@code {*...*}} 占位符由客户端翻译；CrT 自定义原文无对应键时原样显示。
         */
        private static void addControllerFailureDetails(TileMultiblockMachineController controller, IProbeInfo info) {
            CraftingStatus status = controller.getControllerStatus();
            if (status == null || status.isCrafting()) {
                return;
            }
            List<String> details = FailureDetails.get(controller);
            if (details.size() <= 1) {
                return;
            }
            String statusMessage = status.getUnlocMessage();
            if (!details.contains(statusMessage)) {
                return;
            }
            text(info, "{*top.mmceaddition.failure_details*}");
            for (String detail : details) {
                if (detail.equals(statusMessage)) {
                    continue;
                }
                text(info, "§7- {*" + detail + "*}");
            }
        }

        /**
         * 网络连接状态行（已连接/未连接，颜色在语言文件中定义）。
         */
        private static void addNetworkLine(IProbeInfo info, boolean active) {
            text(info, "{*top.mmceaddition.network*}: "
                    + (active ? "{*top.mmceaddition.online*}" : "{*top.mmceaddition.offline*}"));
        }

        /**
         * 缓冲内容行：标签 + 种类数 + 总量（单位由语言键决定）。
         */
        private static void addBufferLine(IProbeInfo info, String labelKey, long kinds, long total, String unitKey) {
            text(info, "{*" + labelKey + "*}: " + kinds + " {*top.mmceaddition.kinds*} / "
                    + fmt(total) + " {*" + unitKey + "*}");
        }

        /**
         * 拼装 "X 个 / Y mB"，为 0 的部分省略。
         */
        private static String itemsAndFluids(long items, long fluids) {
            if (items > 0 && fluids > 0) {
                return fmt(items) + " {*top.mmceaddition.unit_items*} / " + fmt(fluids) + " {*top.mmceaddition.unit_mb*}";
            } else if (items > 0) {
                return fmt(items) + " {*top.mmceaddition.unit_items*}";
            }
            return fmt(fluids) + " {*top.mmceaddition.unit_mb*}";
        }

        private static void text(IProbeInfo info, String s) {
            info.text(s);
        }

        private static String fmt(long n) {
            return String.format("%,d", n);
        }
    }
}

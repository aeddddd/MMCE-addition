package com.github.aeddddd.mmceaddition.command;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.tile.TileMEAsyncItemOutputBus;
import github.kasuminova.mmce.common.tile.MEItemOutputBus;
import hellfirepvp.modularmachinery.common.util.IOInventory;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

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
        return "/mmceaddition replaceMeItemBus";
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
        if (args.length == 0 || !"replaceMeItemBus".equalsIgnoreCase(args[0])) {
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

    @Override
    @Nonnull
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
                                           @Nonnull String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "replaceMeItemBus");
        }
        return Collections.emptyList();
    }
}

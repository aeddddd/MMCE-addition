package com.github.aeddddd.mmceaddition.event;

import com.github.aeddddd.mmceaddition.MMCEAddition;
import com.github.aeddddd.mmceaddition.config.MMCEAdditionConfig;
import com.github.aeddddd.mmceaddition.parallel.FakeParallelMigrator;
import github.kasuminova.mmce.common.event.machine.MachineStructureFormedEvent;
import github.kasuminova.mmce.common.event.machine.MachineStructureUpdateEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 伪并行迁移的结构事件处理器。
 * <p>
 * 结构成型或结构内容更新（例如玩家把 t2 线圈升级为 t3 后结构重检通过）时，
 * 重新计算控制器当前的迁移并行度并写入缓存，
 * 使并行度“随方块升级”自动变化，全程不访问世界、零 tick 开销。
 */
@Mod.EventBusSubscriber(modid = MMCEAddition.MODID)
public class FakeParallelMigrationHandler {

    @SubscribeEvent
    public static void onStructureFormed(MachineStructureFormedEvent event) {
        if (MMCEAdditionConfig.enableFakeParallelMigration) {
            FakeParallelMigrator.recomputeForController(event.getController());
        }
    }

    @SubscribeEvent
    public static void onStructureUpdate(MachineStructureUpdateEvent event) {
        if (MMCEAdditionConfig.enableFakeParallelMigration) {
            FakeParallelMigrator.recomputeForController(event.getController());
        }
    }
}

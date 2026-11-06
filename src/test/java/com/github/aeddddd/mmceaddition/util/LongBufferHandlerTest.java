package com.github.aeddddd.mmceaddition.util;

import com.github.aeddddd.mmceaddition.MinecraftBootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出侧缓冲区包装的守恒回归测试：{@link LongBufferItemHandler} 与 {@link LongBufferFluidHandler}。
 * <p>
 * 与输入侧（{@link LongInputItemHandler}）相对照，输出侧的约定是：
 * <ul>
 *   <li>只进不出：任何 { drain / extractItem } 都必须拿不到东西，否则机器产出的中间物会被外部抽走。</li>
 *   <li>对外槽位恒为"空"，容量完全由 Long 缓冲决定，不受 200 个可见槽位限制。</li>
 *   <li>{@code copy()} 必须复制出独立的缓冲（MMCE 配方检查会用副本，不能污染真实库存）。</li>
 * </ul>
 */
class LongBufferHandlerTest extends MinecraftBootstrap {

    private static void seedItems(LongItemBuffer buffer, long count) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound entry = new NBTTagCompound();
        stack(Items.STICK, 1).writeToNBT(entry);
        entry.setLong("Count", count);
        list.appendTag(entry);
        root.setTag("Items", list);
        buffer.readFromNBT(root);
    }

    private static void seedFluid(LongFluidBuffer buffer, long amount) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("FluidName", FluidRegistry.WATER.getName());
        entry.setLong("Amount", amount);
        list.appendTag(entry);
        root.setTag("Fluids", list);
        buffer.readFromNBT(root);
    }

    // ---------------------------------------------------------------- 物品输出侧

    @Test
    @DisplayName("输出侧 insertItem 把物品累加进 Long 缓冲，且对外槽位恒为空")
    void itemHandler_insertAccumulatesAndHidesSlots() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongBufferItemHandler handler = new LongBufferItemHandler(buffer);

        assertEquals(200, handler.getSlots(), "输出侧同样暴露 200 个可见槽位以通过 MMCE 校验");
        assertTrue(handler.insertItem(0, stack(Items.STICK, 5), false).isEmpty(), "容量充足时必须收下");
        assertTrue(handler.insertItem(7, stack(Items.STICK, 6), false).isEmpty(), "槽位参数不影响记账");
        assertEquals(11L, buffer.getAmount(variantOf(Items.STICK)), "数量全部累加到同一个缓冲");

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            assertTrue(handler.getStackInSlot(slot).isEmpty(), "输出侧不按槽位存储，对外必须是空");
        }
    }

    @Test
    @DisplayName("输出侧 insertItem 的 simulate 不改动缓冲")
    void itemHandler_simulateDoesNotChangeBuffer() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongBufferItemHandler handler = new LongBufferItemHandler(buffer);

        assertTrue(handler.insertItem(0, stack(Items.STICK, 9), true).isEmpty(), "模拟插入应报告可容纳");
        assertTrue(buffer.isEmpty(), "模拟插入不得改动缓冲");
    }

    @Test
    @DisplayName("输出侧已达 Long 上限时整堆退还")
    void itemHandler_rejectsWholeStackAtLongMax() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongBufferItemHandler handler = new LongBufferItemHandler(buffer);
        seedItems(buffer, Long.MAX_VALUE);

        ItemStack rejected = handler.insertItem(0, stack(Items.STICK, 64), false);
        assertEquals(64, rejected.getCount(), "已达 Long 上限时必须整堆退还");
        assertEquals(Long.MAX_VALUE, buffer.getAmount(variantOf(Items.STICK)), "拒收不得改动库存");
        assertEquals(64, handler.insertItem(0, stack(Items.STICK, 64), true).getCount(),
                "模拟判定同样应报告放不下");
    }

    @Test
    @DisplayName("输出侧不允许抽取（只进不出）")
    void itemHandler_extractItemReturnsEmpty() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongBufferItemHandler handler = new LongBufferItemHandler(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);

        assertTrue(handler.extractItem(0, 10, false).isEmpty(), "输出仓不得被外部抽取");
        assertTrue(handler.extractItem(0, 1, true).isEmpty(), "模拟抽取同样不允许");
        assertEquals(10L, buffer.getAmount(variantOf(Items.STICK)), "抽取失败不得改动库存");
    }

    @Test
    @DisplayName("输出侧 setStackInSlot 把非空堆视为一次插入，空堆不产生效果")
    void itemHandler_setStackInSlotInsertsNonEmptyOnly() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongBufferItemHandler handler = new LongBufferItemHandler(buffer);

        handler.setStackInSlot(0, stack(Items.STICK, 3));
        assertEquals(3L, buffer.getAmount(variantOf(Items.STICK)), "非空堆应被视为插入");
        handler.setStackInSlot(0, ItemStack.EMPTY);
        assertEquals(3L, buffer.getAmount(variantOf(Items.STICK)), "空堆不得改动缓冲");
    }

    @Test
    @DisplayName("输出侧 copy() 继承本体库存且与本体隔离")
    void itemHandler_copyCarriesContentAndIsolatesBuffers() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongBufferItemHandler handler = new LongBufferItemHandler(buffer);
        seedItems(buffer, Long.MAX_VALUE - 5);

        LongBufferItemHandler copy = (LongBufferItemHandler) handler.copy();
        // 副本继承了 MAX-5 的库存，因此只能再收下 5 个；若副本是空缓冲，这里会整堆收下
        assertEquals(5, copy.insertItem(0, stack(Items.STICK, 10), false).getCount(),
                "副本必须继承本体库存");
        assertEquals(Long.MAX_VALUE - 5, buffer.getAmount(variantOf(Items.STICK)),
                "写副本不得影响本体（配方检查期必须无副作用）");
    }

    // ---------------------------------------------------------------- 流体输出侧

    @Test
    @DisplayName("输出侧 fill 直接委托给 Long 缓冲并返回实际接受的 mB")
    void fluidHandler_fillDelegates() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        LongBufferFluidHandler handler = new LongBufferFluidHandler(buffer);

        assertEquals(100, handler.fill(new FluidStack(FluidRegistry.WATER, 100), true), "应返回接受的 mB");
        assertEquals(100L, buffer.getAmount(FluidRegistry.WATER));
        assertEquals(40, handler.fill(new FluidStack(FluidRegistry.WATER, 40), false), "模拟填充也应返回结果");
        assertEquals(100L, buffer.getAmount(FluidRegistry.WATER), "模拟填充不得改动缓冲");
    }

    @Test
    @DisplayName("输出侧 drain 恒为 null（不允许抽取）")
    void fluidHandler_drainReturnsNull() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        LongBufferFluidHandler handler = new LongBufferFluidHandler(buffer);
        handler.fill(new FluidStack(FluidRegistry.WATER, 100), true);

        assertNull(handler.drain(new FluidStack(FluidRegistry.WATER, 10), true), "输出仓不得按资源抽取");
        assertNull(handler.drain(10, true), "输出仓不得按数量抽取");
        assertEquals(100L, buffer.getAmount(FluidRegistry.WATER), "抽取被拒后库存不得变化");
    }

    @Test
    @DisplayName("输出侧 getTankProperties 暴露 9 个槽位，内容按 int 上限钳制")
    void fluidHandler_tankPropertiesClampContent() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        LongBufferFluidHandler handler = new LongBufferFluidHandler(buffer);
        seedFluid(buffer, 2L * Integer.MAX_VALUE);

        IFluidTankProperties[] properties = handler.getTankProperties();
        assertEquals(9, properties.length, "与原版 MEFluidBus 一致，固定暴露 9 个槽位");
        FluidStack content = properties[0].getContents();
        assertNotNull(content, "缓冲非空时第一个槽位应有内容");
        assertEquals(FluidRegistry.WATER, content.getFluid(), "流体类型必须一致");
        assertEquals(Integer.MAX_VALUE, content.amount, "对外数量必须被钳制到 int 上限");
        assertEquals(Integer.MAX_VALUE, properties[0].getCapacity());
        for (int i = 1; i < properties.length; i++) {
            assertNull(properties[i].getContents(), "剩余槽位应为空");
        }
    }
}

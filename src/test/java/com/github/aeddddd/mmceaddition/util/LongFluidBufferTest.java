package com.github.aeddddd.mmceaddition.util;

import com.github.aeddddd.mmceaddition.MinecraftBootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongFluidBuffer} 的数量守恒回归测试（对应性质 A.3 / A.4 / A.5 / A.6 / A.7 的流体部分）。
 * <p>
 * 不变量总览：
 * <ul>
 *   <li>{@code fill} 返回"实际接受的 mB"；达到 {@link Long#MAX_VALUE} 时只接受剩余空间。</li>
 *   <li>{@code extract} 返回"实际取走的 mB"，被钳制在持有量之内。</li>
 *   <li>{@code wouldAcceptAll} 是无副作用的预判，结论必须与 {@code fill} 的实际行为一致。</li>
 * </ul>
 */
class LongFluidBufferTest extends MinecraftBootstrap {

    private static final Fluid WATER = FluidRegistry.WATER;

    /** 按 NBT 播种流体缓冲，用于构造"逼近 Long 上限"这类 fill 无法廉价到达的状态。 */
    private static void seed(LongFluidBuffer buffer, String fluidName, long amount) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("FluidName", fluidName);
        entry.setLong("Amount", amount);
        list.appendTag(entry);
        root.setTag("Fluids", list);
        buffer.readFromNBT(root);
    }

    // ---------------------------------------------------------------- A.7 fill

    @Test
    @DisplayName("fill 返回实际接受的 mB 并累加同类流体")
    void fill_returnsAcceptedAmountAndAccumulates() {
        LongFluidBuffer buffer = new LongFluidBuffer();

        assertEquals(100, buffer.fill(new FluidStack(WATER, 100), true), "容量充足时应整份接受");
        assertEquals(100L, buffer.getAmount(WATER));
        assertEquals(50, buffer.fill(new FluidStack(WATER, 50), true), "同类流体累加");
        assertEquals(150L, buffer.getAmount(WATER), "总量 = 两次填充之和");
        assertEquals(150L, buffer.getTotalAmount());
    }

    @Test
    @DisplayName("fill 的模拟模式只返回结果、不改动缓冲")
    void fill_simulateDoesNotChangeState() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, 100), true);

        assertEquals(60, buffer.fill(new FluidStack(WATER, 60), false), "模拟也应返回会被接受的数量");
        assertEquals(100L, buffer.getAmount(WATER), "模拟不得改动缓冲");
    }

    @Test
    @DisplayName("fill 传入 null 或非正数量返回 0")
    void fill_nullOrNonPositive_returnsZero() {
        LongFluidBuffer buffer = new LongFluidBuffer();

        assertEquals(0, buffer.fill(null, true), "null 资源应被安全忽略");
        assertEquals(0, buffer.fill(new FluidStack(WATER, 0), true), "0 mB 应被忽略");
        assertEquals(0, buffer.fill(new FluidStack(WATER, -100), true), "负数量应被忽略");
        assertTrue(buffer.isEmpty(), "以上调用都不得改动缓冲");
    }

    @Test
    @DisplayName("逼近 Long.MAX_VALUE 时 fill 只接受剩余空间")
    void fill_nearLongMax_acceptsOnlyRemainingSpace() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        seed(buffer, WATER.getName(), Long.MAX_VALUE - 20);
        assertEquals(Long.MAX_VALUE - 20, buffer.getAmount(WATER));

        assertEquals(20, buffer.fill(new FluidStack(WATER, 100), true), "只接受剩余空间 20 mB");
        assertEquals(Long.MAX_VALUE, buffer.getAmount(WATER), "溢出分支应把数量饱和到 Long.MAX_VALUE");

        assertEquals(0, buffer.fill(new FluidStack(WATER, 1), true), "已达上限时接受 0 mB");
        assertEquals(Long.MAX_VALUE, buffer.getAmount(WATER), "饱和后数量不变");
        assertFalse(buffer.isEmpty());
    }

    // ---------------------------------------------------------------- A.1 / A.2 extract

    @Test
    @DisplayName("extract 返回实际抽取量并被钳制在持有量之内")
    void extract_returnsActualAmountAndClamps() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, 100), true);

        assertEquals(30L, buffer.extract(WATER, 30), "库存充足时按请求量抽取");
        assertEquals(70L, buffer.getAmount(WATER));

        assertEquals(70L, buffer.extract(WATER, 999), "超过持有量时钳制到持有量");
        assertEquals(0L, buffer.getAmount(WATER), "不应把缓冲扣成负值");
        assertTrue(buffer.isEmpty(), "抽空后 isEmpty() 应为 true");
    }

    @Test
    @DisplayName("extract 传入 0 / 负数 / 未知流体都是空操作")
    void extract_zeroNegativeOrUnknown_isNoOp() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, 40), true);
        Fluid lava = FluidRegistry.LAVA;

        assertEquals(0L, buffer.extract(WATER, 0));
        assertEquals(0L, buffer.extract(WATER, -1));
        assertEquals(0L, buffer.extract(lava, 10), "未知流体没有可取的量");
        assertEquals(40L, buffer.getAmount(WATER), "空操作不得改动缓冲");
    }

    // ---------------------------------------------------------------- A.7 wouldAcceptAll

    @Test
    @DisplayName("wouldAcceptAll 的结论与 fill 的实际行为一致")
    void wouldAcceptAll_matchesFillBehaviour() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, 100), true);

        FluidStack accept = new FluidStack(WATER, 50);
        assertTrue(buffer.wouldAcceptAll(new FluidStack[]{accept}), "50 mB 能被完整接受");
        assertEquals(50, buffer.fill(accept, true), "预判为真时 fill 必须整份接受");
        assertEquals(150L, buffer.getAmount(WATER));

        FluidStack tooMuch = new FluidStack(WATER, Integer.MAX_VALUE);
        seed(buffer, WATER.getName(), Long.MAX_VALUE - 10);
        assertFalse(buffer.wouldAcceptAll(new FluidStack[]{tooMuch}), "超出 Long 上限时预判为假");
        assertEquals(10, buffer.fill(tooMuch, true), "预判为假时 fill 只能接受部分");
    }

    @Test
    @DisplayName("wouldAcceptAll 会累计同批次的同种流体")
    void wouldAcceptAll_accumulatesWithinBatch() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        seed(buffer, WATER.getName(), Long.MAX_VALUE - 30);

        // 单看每一份都放得下（各 20 mB），但两份相加超过剩余空间 30 mB。
        FluidStack first = new FluidStack(WATER, 20);
        FluidStack second = new FluidStack(WATER, 20);
        assertTrue(buffer.wouldAcceptAll(new FluidStack[]{first}), "单份 20 mB 放得下");
        assertFalse(buffer.wouldAcceptAll(new FluidStack[]{first, second}),
                "同批次累计后超出剩余空间，必须判定为放不下");

        // 剩余空间恰好够的场景：只接受剩余空间的 fill 行为与预判保持一致
        assertTrue(buffer.wouldAcceptAll(new FluidStack[]{first, new FluidStack(WATER, 10)}));
    }

    @Test
    @DisplayName("wouldAcceptAll 是无副作用的只读判定")
    void wouldAcceptAll_isReadOnly() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, 10), true);

        assertTrue(buffer.wouldAcceptAll(new FluidStack[]{new FluidStack(WATER, 5)}));
        assertEquals(10L, buffer.getAmount(WATER), "预判不得改动缓冲");
    }

    @Test
    @DisplayName("wouldAcceptAll 容忍 null 与空堆")
    void wouldAcceptAll_ignoresNullAndEmpty() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        assertTrue(buffer.wouldAcceptAll(new FluidStack[]{null, new FluidStack(WATER, 0)}));
        assertTrue(buffer.isEmpty());
    }

    // ---------------------------------------------------------------- A.5 snapshot / A.6 NBT

    @Test
    @DisplayName("snapshot 返回副本，与真实缓冲互不影响")
    void snapshot_isDefensiveCopy() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, 5), true);

        Map<Fluid, Long> snapshot = buffer.snapshot();
        assertNotSame(snapshot, buffer.snapshot(), "每次都是新的副本");
        assertEquals(5L, snapshot.get(WATER));

        buffer.fill(new FluidStack(WATER, 5), true);
        assertEquals(5L, snapshot.get(WATER), "快照不随缓冲变化");

        snapshot.put(WATER, 999L);
        assertEquals(10L, buffer.getAmount(WATER), "改快照不影响真实缓冲");
    }

    @Test
    @DisplayName("NBT 往返保留超过 Integer.MAX_VALUE 的流体量")
    void nbtRoundTrip_preservesLongAmounts() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        buffer.fill(new FluidStack(WATER, Integer.MAX_VALUE), true);
        buffer.fill(new FluidStack(WATER, Integer.MAX_VALUE), true);
        buffer.fill(new FluidStack(FluidRegistry.LAVA, 6), true);

        long expected = 2L * Integer.MAX_VALUE;
        assertEquals(expected, buffer.getAmount(WATER));

        NBTTagCompound tag = new NBTTagCompound();
        buffer.writeToNBT(tag);

        LongFluidBuffer restored = new LongFluidBuffer();
        restored.readFromNBT(tag);
        assertEquals(expected, restored.getAmount(WATER), "大数量必须按 long 原样往返");
        assertEquals(6L, restored.getAmount(FluidRegistry.LAVA), "不同流体分开存储");
        assertEquals(buffer.getTotalAmount(), restored.getTotalAmount());

        restored.readFromNBT(tag);
        assertEquals(expected, restored.getAmount(WATER), "重复读入不得翻倍");
    }

    @Test
    @DisplayName("NBT 里的未知流体或非正数量条目被跳过")
    void readFromNBT_skipsUnknownFluidAndNonPositiveAmount() {
        LongFluidBuffer buffer = new LongFluidBuffer();
        seed(buffer, WATER.getName(), 12);

        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setString("FluidName", "mmceaddition_definitely_not_a_fluid");
        unknown.setLong("Amount", 100);
        list.appendTag(unknown);
        NBTTagCompound zero = new NBTTagCompound();
        zero.setString("FluidName", WATER.getName());
        zero.setLong("Amount", 0);
        list.appendTag(zero);
        root.setTag("Fluids", list);

        buffer.readFromNBT(root);
        assertTrue(buffer.isEmpty(), "未知流体与非正数量条目都应被跳过");
    }
}

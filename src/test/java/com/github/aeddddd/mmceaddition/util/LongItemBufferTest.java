package com.github.aeddddd.mmceaddition.util;

import com.github.aeddddd.mmceaddition.MinecraftBootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongItemBuffer} 的数量守恒回归测试（对应性质 A.1 ~ A.6）。
 * <p>
 * 不变量总览：
 * <ul>
 *   <li>{@code extract} 返回的是"实际取走的数量"，永远不会超过持有量，缓冲区永远不会变成负值。</li>
 *   <li>{@code insert} 在逼近 {@link Long#MAX_VALUE} 时是"饱和"语义：只接受剩余空间，其余退还。</li>
 *   <li>{@code snapshot()} 是拷贝，NBT 往返必须原样保留超过 {@code Integer.MAX_VALUE} 的数量。</li>
 * </ul>
 */
class LongItemBufferTest extends MinecraftBootstrap {

    private static final ItemVariant STICK = variantOf(Items.STICK);

    /** 直接按 NBT 播种缓冲区，用于构造"已经逼近 Long 上限"等用 insert 无法廉价到达的状态。 */
    private static void seed(LongItemBuffer buffer, ItemStack sample, long count) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound entry = new NBTTagCompound();
        sample.writeToNBT(entry);
        entry.setLong("Count", count);
        list.appendTag(entry);
        root.setTag("Items", list);
        buffer.readFromNBT(root);
    }

    // ---------------------------------------------------------------- A.1 / A.2

    @Test
    @DisplayName("insert 累加同类物品，extract 返回实际取出的数量")
    void insertThenExtract_accumulatesAndReturnsActualAmount() {
        LongItemBuffer buffer = new LongItemBuffer();

        assertTrue(buffer.insert(stack(Items.STICK, 10)).isEmpty(), "容量充足时应整堆收下");
        assertEquals(10L, buffer.getAmount(STICK), "插入后总量应等于插入量");

        // 请求 4，库存充足 → 取走 4，剩余 6
        assertEquals(4L, buffer.extract(STICK, 4), "extract 返回实际取走的数量");
        assertEquals(6L, buffer.getAmount(STICK), "剩余量 = 原量 - 取出量");
        assertEquals(6L, buffer.getTotalAmount(), "总数量与单变体数量一致");
    }

    @Test
    @DisplayName("extract 请求量超过持有量时返回持有量并清空缓冲")
    void extract_moreThanHeld_returnsHeldAmountAndEmptiesBuffer() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, 10));

        assertEquals(10L, buffer.extract(STICK, 999), "应钳制到持有量，而不是返回请求量");
        assertEquals(0L, buffer.getAmount(STICK), "缓冲不应被扣成负值");
        assertTrue(buffer.isEmpty(), "取空后 isEmpty() 应为 true");
        assertEquals(0L, buffer.extract(STICK, 5), "空缓冲再取应返回 0");
    }

    @Test
    @DisplayName("插入不同变体互不影响各自的数量")
    void insert_differentVariants_areAggregatedSeparately() {
        LongItemBuffer buffer = new LongItemBuffer();
        ItemVariant coal = taggedVariant(Items.COAL, 1);

        buffer.insert(stack(Items.STICK, 7));
        buffer.insert(taggedStack(Items.COAL, 1, 3));
        buffer.insert(stack(Items.STICK, 5));

        assertEquals(12L, buffer.getAmount(STICK), "同类物品合并计数");
        assertEquals(3L, buffer.getAmount(coal), "不同变体独立计数");
        assertEquals(15L, buffer.getTotalAmount(), "总数量是各变体之和");
    }

    // ---------------------------------------------------------------- A.3 逼近 Long 上限

    @Test
    @DisplayName("逼近 Long.MAX_VALUE 时 insert 饱和：只接受剩余空间并退还余量")
    void insert_nearLongMax_saturatesAndReturnsRemainder() {
        LongItemBuffer buffer = new LongItemBuffer();
        seed(buffer, stack(Items.STICK, 1), Long.MAX_VALUE - 5);
        assertEquals(Long.MAX_VALUE - 5, buffer.getAmount(STICK));

        ItemStack remainder = buffer.insert(stack(Items.STICK, 10));
        assertEquals(5, remainder.getCount(), "只接受剩余空间 5，其余 5 个必须原样退还");
        assertEquals(Items.STICK, remainder.getItem(), "退还的必须是同种物品");
        // 饱和语义：缓冲区钉在 Long.MAX_VALUE，不会溢出成负数
        assertEquals(Long.MAX_VALUE, buffer.getAmount(STICK), "溢出分支应把数量饱和到 Long.MAX_VALUE");
        assertFalse(buffer.isEmpty(), "饱和后缓冲区仍非空");

        ItemStack fullReject = buffer.insert(stack(Items.STICK, 1));
        assertEquals(1, fullReject.getCount(), "已达上限时整堆退还");
        assertEquals(Long.MAX_VALUE, buffer.getAmount(STICK), "再次插入不得改变已饱和的数量");
    }

    @Test
    @DisplayName("单变体达上限时，其它变体仍能正常插入")
    void insert_otherVariantStillAcceptedWhenOneIsSaturated() {
        LongItemBuffer buffer = new LongItemBuffer();
        seed(buffer, stack(Items.STICK, 1), Long.MAX_VALUE);

        assertTrue(buffer.insert(stack(Items.COAL, 8)).isEmpty(), "容量是按变体独立计算的");
        assertEquals(8L, buffer.getAmount(variantOf(Items.COAL)));
        assertEquals(Long.MAX_VALUE, buffer.getTotalAmount(), "总数量意外溢出时按饱和上报");
    }

    @Test
    @DisplayName("多变体总量超过 Long 上限时 getTotalAmount 返回上限而不是负数")
    void getTotalAmount_saturatesInsteadOfGoingNegative() {
        LongItemBuffer buffer = new LongItemBuffer();
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();

        NBTTagCompound first = new NBTTagCompound();
        stack(Items.STICK, 1).writeToNBT(first);
        first.setLong("Count", 5_000_000_000_000_000_000L);
        list.appendTag(first);

        NBTTagCompound second = new NBTTagCompound();
        stack(Items.COAL, 1).writeToNBT(second);
        second.setLong("Count", 5_000_000_000_000_000_000L);
        list.appendTag(second);
        root.setTag("Items", list);
        buffer.readFromNBT(root);

        // 两个 5e18 相加会越过 Long.MAX_VALUE（约 9.22e18）；无论 HashMap 迭代顺序如何都会溢出，
        // 因此返回值必须是饱和上限，绝不能是负数（否则调用方会把负数当"无库存"处理）。
        assertEquals(Long.MAX_VALUE, buffer.getTotalAmount(), "总和溢出时必须饱和为 Long.MAX_VALUE");
    }

    // ---------------------------------------------------------------- A.4 0 与负数

    @Test
    @DisplayName("extract 传入 0 或负数是无副作用的空操作")
    void extract_zeroOrNegativeAmount_isNoOp() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, 10));

        assertEquals(0L, buffer.extract(STICK, 0), "取 0 应返回 0");
        assertEquals(0L, buffer.extract(STICK, -5), "取负数是空操作");
        assertEquals(10L, buffer.getAmount(STICK), "空操作不得改动缓冲量");
    }

    @Test
    @DisplayName("插入空堆、数量为 0 或负数的堆都是空操作")
    void insert_emptyStack_isNoOp() {
        LongItemBuffer buffer = new LongItemBuffer();

        assertTrue(buffer.insert(ItemStack.EMPTY).isEmpty(), "插入空堆返回空堆");
        assertTrue(buffer.insert(stack(Items.STICK, 0)).isEmpty(), "数量 0 的堆同样视作空");
        assertTrue(buffer.insert(stack(Items.STICK, -5)).isEmpty(), "负数量堆视作空（ItemStack.isEmpty 契约）");
        assertTrue(buffer.isEmpty(), "空操作不得让缓冲区变成非空");
        assertEquals(0L, buffer.getAmount(STICK));
        // 守护：一旦负数量被写进缓冲，总量会变成负数，注入端会把它当成"没有库存"而丢弃货物
        assertEquals(0L, buffer.getTotalAmount(), "缓冲里绝不能出现负数库存");
    }

    @Test
    @DisplayName("extract 未知变体返回 0 且不影响已有库存")
    void extract_unknownVariant_returnsZero() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, 4));

        assertEquals(0L, buffer.extract(variantOf(Items.COAL), 10), "未知变体没有可取的量");
        assertEquals(4L, buffer.getAmount(STICK), "其它变体的库存不受影响");
    }

    // ---------------------------------------------------------------- A.5 snapshot

    @Test
    @DisplayName("snapshot 返回副本：改缓冲不影响快照，改快照不影响缓冲")
    void snapshot_isDefensiveCopy() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, 3));

        Map<ItemVariant, Long> snapshot = buffer.snapshot();
        assertNotNull(snapshot);
        assertEquals(3L, snapshot.get(STICK));

        buffer.insert(stack(Items.STICK, 7));
        assertEquals(3L, snapshot.get(STICK), "快照不应随缓冲变化（遍历注入依赖这一点）");

        snapshot.put(STICK, 999L);
        assertEquals(10L, buffer.getAmount(STICK), "改快照不得影响真实缓冲");
    }

    @Test
    @DisplayName("clear 清空全部变体")
    void clear_emptiesEverything() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, 3));
        buffer.insert(stack(Items.COAL, 4));

        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertEquals(0L, buffer.getTotalAmount());
        assertEquals(0L, buffer.getAmount(STICK));
    }

    // ---------------------------------------------------------------- A.6 NBT 往返

    @Test
    @DisplayName("NBT 往返保留超过 Integer.MAX_VALUE 的数量与物品变体")
    void nbtRoundTrip_preservesLongCountsAndVariants() {
        LongItemBuffer buffer = new LongItemBuffer();
        // 单次 insert 的数量是 int，但总量可以累加突破 int 上限——这正是本模组存在的理由。
        buffer.insert(stack(Items.STICK, Integer.MAX_VALUE));
        buffer.insert(stack(Items.STICK, Integer.MAX_VALUE));
        buffer.insert(taggedStack(Items.COAL, 42, 7));

        long expectedStick = 2L * Integer.MAX_VALUE;
        assertEquals(expectedStick, buffer.getAmount(STICK), "总量应突破 int 上限");
        assertTrue(expectedStick > Integer.MAX_VALUE);

        NBTTagCompound tag = new NBTTagCompound();
        buffer.writeToNBT(tag);

        LongItemBuffer restored = new LongItemBuffer();
        restored.readFromNBT(tag);
        assertEquals(expectedStick, restored.getAmount(STICK), "大数量必须按 long 原样往返");
        assertEquals(7L, restored.getAmount(taggedVariant(Items.COAL, 42)), "NBT 变体必须能区分");
        assertEquals(buffer.getTotalAmount(), restored.getTotalAmount(), "总量一致");

        // 同一个 NBT 里再读一次也必须得到相同的量（readFromNBT 是覆盖语义，不能叠加、不能缩水）。
        // 这里用重新序列化出来的新 compound 复现"正常的第二次读取"。
        LongItemBuffer reread = new LongItemBuffer();
        NBTTagCompound fresh = new NBTTagCompound();
        buffer.writeToNBT(fresh);
        reread.readFromNBT(fresh);
        assertEquals(expectedStick, reread.getAmount(STICK), "重复读入不得改变数量");
    }

    /**
     * 回归测试：{@code readFromNBT} 曾经就地改写传入的 {@link NBTTagCompound}。
     * <p>
     * 旧实现在 {@code LongItemBuffer.java:184} 为了复用 {@code new ItemStack(tag)}，把条目里的
     * {@code Count} 从 long 直接改写成 int 的 1，而该 compound 是调用方（NBT 列表）持有的活引用。
     * 于是同一个 compound 第二次读入时，每个变体的数量都会变成 1，超上限的大数量随之丢失。
     * 现在改为在 {@code tag.copy()} 上做规范化，传入的 NBT 保持原样。
     */
    @Test
    @DisplayName("同一个 NBT compound 重复读入必须得到相同数量")
    void nbtRoundTrip_sameCompoundReadTwice_keepsLongCounts() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, Integer.MAX_VALUE));
        buffer.insert(stack(Items.STICK, Integer.MAX_VALUE));

        NBTTagCompound tag = new NBTTagCompound();
        buffer.writeToNBT(tag);

        LongItemBuffer first = new LongItemBuffer();
        first.readFromNBT(tag);
        assertEquals(2L * Integer.MAX_VALUE, first.getAmount(STICK));

        LongItemBuffer second = new LongItemBuffer();
        second.readFromNBT(tag);
        assertEquals(2L * Integer.MAX_VALUE, second.getAmount(STICK),
                "readFromNBT 不得改动传入的 compound，否则第二次读入会把数量降为 1");
    }

    @Test
    @DisplayName("读入没有 Items 键的 NBT 会清空缓冲")
    void readFromNBT_withoutItemsKey_clearsBuffer() {
        LongItemBuffer buffer = new LongItemBuffer();
        buffer.insert(stack(Items.STICK, 5));

        buffer.readFromNBT(new NBTTagCompound());
        assertTrue(buffer.isEmpty(), "readFromNBT 语义是整体覆盖");
    }
}

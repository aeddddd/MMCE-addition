package com.github.aeddddd.mmceaddition.util;

import com.github.aeddddd.mmceaddition.MinecraftBootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LongInputItemHandler} 的槽位记账回归测试（对应性质 B.1 ~ B.9）。
 * <p>
 * 背景：MMCE 的配方消耗不走 {@code extractItem}，而是以 {@code setStackInSlot} 作为最终落点
 * （完全消耗 → EMPTY；部分消耗 → 可见数量 - 消耗数）。因此 handler 必须在
 * {@code setStackInSlot} 里按"目标数量与当前可见数量的差额"调整缓冲区：
 * 少写一步就会"消耗永远是空操作"（材料不减少），写错方向就会"刷物品"。
 * <p>
 * 本类守护的不变量：
 * <ol>
 *   <li>可见槽位只有 200 个；槽位全被别的变体占满时，新变体必须整堆拒收，且不得部分写入。</li>
 *   <li>{@code wouldAcceptAll} / {@code describeRejection} 的结论必须与 {@code insertAll} 的实际成败一致，
 *       且 {@code insertAll} 是全有或全无。</li>
 *   <li>{@code setStackInSlot} 的差额永远相对"可见量"= {@code min(缓冲量, Integer.MAX_VALUE)} 计算，
 *       缓冲里超出 int 上限的隐藏余量既不能被吞掉，也不能被重复插入。</li>
 * </ol>
 */
class LongInputItemHandlerTest extends MinecraftBootstrap {

    private static final int VISIBLE_SLOTS = 200;

    private static final ItemVariant STICK = variantOf(Items.STICK);

    /** 造一个只在本测试内部使用的 handler（缓冲与 handler 总是成对出现）。 */
    private static LongInputItemHandler handlerFor(LongItemBuffer buffer) {
        return new LongInputItemHandler(buffer);
    }

    /**
     * 把缓冲里 STICK 的数量直接播种到 {@link Long#MAX_VALUE}。
     * <p>
     * 单次 insert 的数量受 int 限制，用 insert 到达 Long 上限需要数十亿次调用，
     * 因此这里走 NBT（数量以 long 存储）构造这个边界状态。
     */
    private static void seedAtLongMax(LongItemBuffer buffer) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound entry = new NBTTagCompound();
        stack(Items.STICK, 1).writeToNBT(entry);
        entry.setLong("Count", Long.MAX_VALUE);
        list.appendTag(entry);
        root.setTag("Items", list);
        buffer.readFromNBT(root);
    }

    /** 统计对外可见的非空槽位数量。 */
    private static int visibleSlotCount(LongInputItemHandler handler) {
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 把对外可见的内容汇总为"变体 → 可见数量"，用于与期望值做顺序无关的比较。 */
    private static Map<ItemVariant, Integer> visibleContents(LongInputItemHandler handler) {
        Map<ItemVariant, Integer> contents = new HashMap<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                contents.put(new ItemVariant(stack), stack.getCount());
            }
        }
        return contents;
    }

    /** 用 200 个互不相同的变体（同物品 + 唯一 NBT）占满全部可见槽位。 */
    private static void occupyAllVisibleSlots(LongInputItemHandler handler) {
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            assertTrue(handler.insertItem(0, taggedStack(Items.STICK, i, 1), false).isEmpty(),
                    "第 " + i + " 个变体应有空槽可落位");
        }
        assertEquals(VISIBLE_SLOTS, visibleSlotCount(handler), "200 个可见槽位应全部被占用");
    }

    /**
     * 逐条校验"预判 = 实际结果"：{@code wouldAcceptAll} 为 true ⟺ {@code insertAll} 成功且缓冲按预期增加；
     * 为 false 时 {@code insertAll} 失败且缓冲零改动。
     */
    private static void assertAcceptanceConsistent(LongInputItemHandler handler, LongItemBuffer buffer,
                                                  ItemStack[] stacks, boolean expected) {
        long beforeTotal = buffer.getTotalAmount();
        Map<ItemVariant, Long> before = buffer.snapshot();

        assertEquals(expected, handler.wouldAcceptAll(stacks), "wouldAcceptAll 的预判结果不符");
        assertEquals(expected, handler.insertAll(stacks), "insertAll 的实际成败必须与预判一致");

        if (expected) {
            long added = 0;
            for (ItemStack stack : stacks) {
                if (stack != null && !stack.isEmpty()) {
                    added += stack.getCount();
                }
            }
            assertEquals(beforeTotal + added, buffer.getTotalAmount(), "判定可写入时缓冲必须按预期增加");
        } else {
            assertEquals(beforeTotal, buffer.getTotalAmount(), "判定不可写入时缓冲总量必须零改动");
            assertEquals(before, buffer.snapshot(), "判定不可写入时不能留下任何部分写入");
        }
    }

    // ---------------------------------------------------------------- B.1

    @Test
    @DisplayName("insertItem 能容纳时返回空堆并把数量记进缓冲")
    void insertItem_acceptsAndAccumulates() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);

        assertTrue(handler.insertItem(0, stack(Items.STICK, 5), false).isEmpty(), "容量足够时必须整堆收下");
        assertEquals(5L, buffer.getAmount(STICK), "缓冲总量应等于插入量");
        assertEquals(5, handler.getStackInSlot(0).getCount(), "对外可见数量应等于缓冲量");

        // 同变体再次插入：合并在同一槽位，数量累加
        assertTrue(handler.insertItem(0, stack(Items.STICK, 3), false).isEmpty());
        assertEquals(8L, buffer.getAmount(STICK), "同类物品必须累加");
        assertEquals(8, handler.getStackInSlot(0).getCount());
        assertEquals(1, visibleSlotCount(handler), "同一变体只能占用一个槽位");
    }

    @Test
    @DisplayName("insertItem 的 simulate 模式只做判定，不改动缓冲")
    void insertItem_simulateDoesNotChangeState() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);

        assertTrue(handler.insertItem(0, stack(Items.STICK, 4), true).isEmpty(), "模拟插入应报告可容纳");
        assertTrue(buffer.isEmpty(), "模拟插入不得写入缓冲");
        assertTrue(handler.getStackInSlot(0).isEmpty(), "模拟插入不得占用槽位");
    }

    // ---------------------------------------------------------------- B.2

    @Test
    @DisplayName("200 个可见槽位被占满时，新变体整堆拒收且缓冲零改动")
    void insertItem_rejectsWholeStackWhenAllVisibleSlotsTaken() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        occupyAllVisibleSlots(handler);

        assertEquals(VISIBLE_SLOTS, buffer.getTotalAmount(), "占位阶段缓冲内容应符合预期");

        ItemStack fresh = taggedStack(Items.STICK, 9999, 7);
        ItemStack rejected = handler.insertItem(0, fresh, false);

        assertEquals(7, rejected.getCount(), "无处落位时必须整堆退还（不能部分写入）");
        assertEquals(Items.STICK, rejected.getItem(), "退还的必须是同一物品");
        assertEquals(0L, buffer.getAmount(new ItemVariant(fresh)), "被拒的新变体不得进入缓冲");
        assertEquals(VISIBLE_SLOTS, buffer.getTotalAmount(), "拒收时缓冲必须完全不变");
        assertFalse(handler.wouldAcceptAll(new ItemStack[]{fresh}), "预判同样应判定为放不下");
    }

    @Test
    @DisplayName("槽位未满时新变体可以落位，且落在请求的 0 号槽")
    void insertItem_placesNewVariantAtRequestedSlot() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        occupyAllVisibleSlots(handler);

        // 先腾出一个槽位（抽空 0 号槽对应的变体），新变体应该能落进 0 号槽
        handler.setStackInSlot(0, ItemStack.EMPTY);
        ItemStack fresh = taggedStack(Items.STICK, 9999, 7);
        assertTrue(handler.insertItem(0, fresh, false).isEmpty(), "有空槽时必须能落位");
        assertEquals(7L, buffer.getAmount(new ItemVariant(fresh)));
        assertEquals(7, handler.getStackInSlot(0).getCount());
    }

    // ---------------------------------------------------------------- B.3 / B.4

    @Test
    @DisplayName("wouldAcceptAll 与 insertAll 的成败一致（含槽位占满与 Long 上限两种情况）")
    void wouldAcceptAll_agreesWithInsertAll() {
        // 情况一：空 handler，三个全新变体 → 全部可写入
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        assertAcceptanceConsistent(handler, buffer, new ItemStack[]{
                taggedStack(Items.STICK, 1, 5),
                taggedStack(Items.STICK, 2, 6),
                taggedStack(Items.COAL, 3, 7)}, true);

        // 情况二：槽位已满，数组里含一个无处落位的新变体 → 全部拒收
        LongItemBuffer fullBuffer = new LongItemBuffer();
        LongInputItemHandler fullHandler = handlerFor(fullBuffer);
        occupyAllVisibleSlots(fullHandler);
        assertAcceptanceConsistent(fullHandler, fullBuffer, new ItemStack[]{
                taggedStack(Items.STICK, 0, 3),
                taggedStack(Items.COAL, 7777, 2)}, false);

        // 情况三：变体已达 Long 上限 → 整个数组拒收
        LongItemBuffer saturatedBuffer = new LongItemBuffer();
        seedAtLongMax(saturatedBuffer);
        LongInputItemHandler saturatedHandler = handlerFor(saturatedBuffer);
        saturatedHandler.syncWithBuffer();
        assertEquals(Long.MAX_VALUE, saturatedBuffer.getAmount(STICK));
        assertAcceptanceConsistent(saturatedHandler, saturatedBuffer,
                new ItemStack[]{stack(Items.STICK, 1)}, false);
        assertEquals(Long.MAX_VALUE, saturatedBuffer.getAmount(STICK), "拒收后原有库存必须原样保留");
    }

    @Test
    @DisplayName("insertAll 全有或全无：其中一组写不进去就一个都不写")
    void insertAll_isAllOrNothing() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        occupyAllVisibleSlots(handler);

        Map<ItemVariant, Long> before = buffer.snapshot();
        ItemStack[] stacks = {
                // 这一组本身写得进去（0 号槽已有该变体）
                taggedStack(Items.STICK, 0, 3),
                // 这一组无处落位
                taggedStack(Items.COAL, 4242, 2)};

        assertFalse(handler.wouldAcceptAll(stacks));
        assertFalse(handler.insertAll(stacks), "只要有一组写不进去，insertAll 必须整体失败");
        assertEquals(before, buffer.snapshot(), "失败时不得留下第一个堆的半成品写入");
        assertEquals(1L, buffer.getAmount(taggedVariant(Items.STICK, 0)), "已有变体的数量不得被改动");
        assertEquals(0L, buffer.getAmount(taggedVariant(Items.COAL, 4242)), "被拒的变体不得进入缓冲");
    }

    @Test
    @DisplayName("describeRejection 说明拒收原因，可写入时返回 null")
    void describeRejection_explainsWhy() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);

        assertNull(handler.describeRejection(new ItemStack[]{stack(Items.STICK, 1)}), "可写入时应返回 null");

        occupyAllVisibleSlots(handler);
        String reason = handler.describeRejection(new ItemStack[]{taggedStack(Items.COAL, 8888, 1)});
        assertNotNull(reason, "拒收时必须给出原因");
        assertTrue(reason.contains(String.valueOf(VISIBLE_SLOTS)), "原因里应说明可见槽位数量：" + reason);
    }

    // ---------------------------------------------------------------- B.5

    @Test
    @DisplayName("setStackInSlot(slot, EMPTY) 真的扣掉该变体的缓冲量")
    void setStackInSlot_emptyTrulyConsumes() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);
        handler.insertItem(1, stack(Items.COAL, 4), false);

        // MMCE 完全消耗一个槽位时的落点
        handler.setStackInSlot(0, ItemStack.EMPTY);

        // 守护历史 bug：旧实现在这里什么都不扣，材料永远消耗不掉，机器会卡在"装配完成但材料还在"。
        assertEquals(0L, buffer.getAmount(STICK), "完全消耗必须真的把物品从缓冲里扣掉");
        assertTrue(handler.getStackInSlot(0).isEmpty(), "扣空后该槽位对外必须为空");
        assertEquals(4L, buffer.getAmount(variantOf(Items.COAL)), "其它变体的库存不得被牵连");
        assertEquals(4, handler.getStackInSlot(1).getCount());
    }

    // ---------------------------------------------------------------- B.6

    @Test
    @DisplayName("setStackInSlot(slot, 剩余量) 只扣差额，不把剩余量再次插入")
    void setStackInSlot_partialConsumptionSubtractsOnlyTheDelta() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);

        // MMCE 部分消耗：它把槽位内容从 10 改成 4，意味着消耗了 6 个
        handler.setStackInSlot(0, stack(Items.STICK, 4));

        // 守护历史 bug：把"剩余量"当成一次新插入会让数量变成 10 + 4 = 14（刷物品）。
        assertEquals(4L, buffer.getAmount(STICK), "剩余量必须被当作目标值，而不是新插入的量");
        assertEquals(4, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("setStackInSlot 提高可见量时按差额补充缓冲")
    void setStackInSlot_increaseAddsOnlyTheDelta() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 4), false);

        handler.setStackInSlot(0, stack(Items.STICK, 9));
        assertEquals(9L, buffer.getAmount(STICK), "新增的可见量应补进缓冲");
        assertEquals(9, handler.getStackInSlot(0).getCount());

        // 差额为 0 时不得产生任何效果
        handler.setStackInSlot(0, stack(Items.STICK, 9));
        assertEquals(9L, buffer.getAmount(STICK), "同数量替换必须是无操作");
    }

    // ---------------------------------------------------------------- B.7

    @Test
    @DisplayName("setStackInSlot 换成不同变体：旧变体被扣掉，新变体写入，不复制")
    void setStackInSlot_otherVariantReplacesWithoutDuplicating() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);

        handler.setStackInSlot(0, stack(Items.COAL, 3));

        assertEquals(0L, buffer.getAmount(STICK), "被替换掉的旧变体必须从缓冲里扣除");
        assertEquals(3L, buffer.getAmount(variantOf(Items.COAL)), "新变体按槽位新内容写入");
        assertEquals(3L, buffer.getTotalAmount(), "替换不是复制：总量应为 3 而不是 13");
        assertEquals(visibleContents(handler), java.util.Collections.singletonMap(variantOf(Items.COAL), 3),
                "对外可见内容应只剩新变体");
    }

    @Test
    @DisplayName("同一变体只能映射一个可见槽位，避免双份显示")
    void setStackInSlot_keepsSingleVisibleSlotPerVariant() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);
        handler.insertItem(1, stack(Items.COAL, 4), false);

        // MMCE 把 0 号槽整槽替换成 COAL：1 号槽那份 COAL 的槽位映射必须让位，否则同一种物品会被算两次
        handler.setStackInSlot(0, stack(Items.COAL, 4));

        assertEquals(8L, buffer.getAmount(variantOf(Items.COAL)), "4（原有）+ 4（新写入）");
        assertEquals(0L, buffer.getAmount(STICK), "旧变体应被完全扣掉");
        assertEquals(1, visibleSlotCount(handler), "同一变体不得同时出现在两个槽位");
        assertEquals(8, handler.getStackInSlot(0).getCount(), "可见数量应汇总到唯一映射的槽位");
        assertTrue(handler.getStackInSlot(1).isEmpty(), "被让位的槽位应重新变为空");
    }

    // ---------------------------------------------------------------- B.8 隐藏余量

    @Test
    @DisplayName("缓冲量超过 int 上限时可见量被钳制，完全消耗只扣可见量、保留隐藏余量")
    void setStackInSlot_keepsHiddenRemainderAboveIntLimit() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        // 两次 Integer.MAX_VALUE 累加 → 4294967294，超过 int 上限（这正是本模组存在的理由）
        handler.insertItem(0, stack(Items.STICK, Integer.MAX_VALUE), false);
        handler.insertItem(0, stack(Items.STICK, Integer.MAX_VALUE), false);

        long total = 2L * Integer.MAX_VALUE;
        assertEquals(total, buffer.getAmount(STICK), "缓冲总量应突破 int 上限");
        assertEquals(Integer.MAX_VALUE, handler.getStackInSlot(0).getCount(),
                "MMCE 只能看到 int 上限内的可见量");

        // 不变量：差额相对"可见量"计算，因此一次完全消耗只扣掉 Integer.MAX_VALUE，
        // 缓冲里超出 int 上限的部分必须留到下一次消耗，不能被凭空吞掉。
        handler.setStackInSlot(0, ItemStack.EMPTY);
        assertEquals(total - Integer.MAX_VALUE, buffer.getAmount(STICK),
                "隐藏余量必须保留，不得被一次消耗吞掉");
        assertEquals(Integer.MAX_VALUE, handler.getStackInSlot(0).getCount(),
                "余量仍超过可见上限，槽位继续暴露 int 上限的数量");

        // 余量再被消耗一次才归零，槽位映射此时必须清空
        handler.setStackInSlot(0, ItemStack.EMPTY);
        assertEquals(0L, buffer.getAmount(STICK), "余量耗尽后缓冲归零");
        assertTrue(handler.getStackInSlot(0).isEmpty(), "归零后槽位必须清空，不能让空变体占位");
    }

    @Test
    @DisplayName("同变体按可见量调小时，隐藏余量补上被消耗的可见量")
    void setStackInSlot_sameVariantWithHiddenRemainder_subtractsVisibleDelta() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, Integer.MAX_VALUE), false);
        handler.insertItem(0, stack(Items.STICK, Integer.MAX_VALUE), false);
        long total = 2L * Integer.MAX_VALUE;

        // MMCE 把可见的 2147483647 改成 5，意味着它认为自己消耗了 2147483642 个
        handler.setStackInSlot(0, stack(Items.STICK, 5));

        long expected = total - (Integer.MAX_VALUE - 5);
        assertEquals(expected, buffer.getAmount(STICK),
                "总量必须按可见差额减少，即保留全部隐藏余量（既不能被吞掉，也不能变成 5）");
        assertEquals(Integer.MAX_VALUE, handler.getStackInSlot(0).getCount(),
                "隐藏余量仍高于 int 上限，因此槽位继续报告上限数量");
    }

    @Test
    @DisplayName("换成其它变体时，旧变体的隐藏余量迁移到空闲槽位而不是变成死库存")
    void setStackInSlot_otherVariantMigratesHiddenRemainder() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, Integer.MAX_VALUE), false);
        handler.insertItem(0, stack(Items.STICK, Integer.MAX_VALUE), false);

        handler.setStackInSlot(0, stack(Items.COAL, 2));

        assertEquals(Integer.MAX_VALUE, buffer.getAmount(STICK), "旧变体只失去可见的那一部分");
        assertEquals(2L, buffer.getAmount(variantOf(Items.COAL)), "新变体按槽位内容写入");
        // 旧变体还有余量，必须换到别的槽位继续暴露，否则这批货在 UI 与配方检查里都看不见
        Map<ItemVariant, Integer> contents = visibleContents(handler);
        assertEquals(2, contents.size(), "旧变体的余量必须迁移到另一个可见槽位");
        assertEquals(2, contents.get(variantOf(Items.COAL)));
        assertEquals(Integer.MAX_VALUE, contents.get(STICK));
    }

    @Test
    @DisplayName("变体已达 Long 上限时 insertItem 整堆退还")
    void insertItem_rejectsWholeStackWhenVariantAtLongMax() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        seedAtLongMax(buffer);
        handler.syncWithBuffer();

        assertEquals(Long.MAX_VALUE, buffer.getAmount(STICK));
        ItemStack rejected = handler.insertItem(0, stack(Items.STICK, 64), false);
        assertEquals(64, rejected.getCount(), "已达 Long 上限时必须整堆退还");
        assertEquals(Long.MAX_VALUE, buffer.getAmount(STICK), "拒收不得改动库存");
    }

    // ---------------------------------------------------------------- B.9 syncWithBuffer

    @Test
    @DisplayName("syncWithBuffer 重建映射后 getStackInSlot 能反映缓冲内容")
    void syncWithBuffer_rebuildsVisibleMapping() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);

        // 绕过 handler 直接写缓冲（等价于从 NBT 加载/外部写入）
        buffer.insert(stack(Items.STICK, 40));
        buffer.insert(taggedStack(Items.COAL, 7, 9));
        assertTrue(handler.getStackInSlot(0).isEmpty(), "未同步时 handler 看不到直接写入的缓冲");

        handler.syncWithBuffer();

        Map<ItemVariant, Integer> contents = visibleContents(handler);
        assertEquals(2, contents.size(), "两个变体应各占一个槽位");
        assertEquals(Integer.valueOf(40), contents.get(STICK), "可见数量必须等于缓冲量");
        assertEquals(Integer.valueOf(9), contents.get(taggedVariant(Items.COAL, 7)));
        assertEquals(2, visibleSlotCount(handler), "不得留下陈旧的槽位映射");
    }

    @Test
    @DisplayName("缓冲抽空后 getStackInSlot 自行清理空映射")
    void getStackInSlot_clearsStaleMappingWhenBufferEmpty() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 6), false);

        buffer.extract(STICK, 6);
        assertTrue(handler.getStackInSlot(0).isEmpty(), "缓冲已空时槽位必须报告为空");
        assertEquals(0, visibleSlotCount(handler));
    }

    @Test
    @DisplayName("syncWithBuffer 清除上一次同步留下的陈旧映射")
    void syncWithBuffer_dropsStaleMappings() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        buffer.insert(stack(Items.STICK, 5));
        buffer.insert(stack(Items.COAL, 5));
        handler.syncWithBuffer();
        assertEquals(2, visibleSlotCount(handler));

        buffer.extract(STICK, 5);
        handler.syncWithBuffer();
        assertEquals(1, visibleSlotCount(handler), "已被抽空的变体不应继续占用槽位");
        assertEquals(java.util.Collections.singletonMap(variantOf(Items.COAL), 5), visibleContents(handler));
    }

    // ---------------------------------------------------------------- 附：extractItem 与 copy

    @Test
    @DisplayName("extractItem 返回被钳制的实际数量并同步扣减缓冲")
    void extractItem_returnsClampedAmountAndReducesBuffer() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);

        ItemStack extracted = handler.extractItem(0, 4, false);
        assertEquals(4, extracted.getCount(), "库存充足时按请求量抽取");
        assertEquals(6L, buffer.getAmount(STICK), "抽取必须同步扣减缓冲");

        ItemStack over = handler.extractItem(0, 999, false);
        assertEquals(6, over.getCount(), "超过持有量时钳制到持有量");
        assertTrue(buffer.isEmpty(), "抽空后缓冲应为空");
        assertTrue(handler.getStackInSlot(0).isEmpty(), "抽空后槽位应清空");
        assertTrue(handler.extractItem(0, 1, false).isEmpty(), "空槽位再抽取应返回空堆");
    }

    @Test
    @DisplayName("extractItem 的 simulate 模式不改动缓冲")
    void extractItem_simulateDoesNotChangeState() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);

        assertEquals(4, handler.extractItem(0, 4, true).getCount(), "模拟抽取也应报告可抽取量");
        assertEquals(10L, buffer.getAmount(STICK), "模拟抽取不得改动缓冲");
    }

    @Test
    @DisplayName("copy() 产出独立缓冲，写副本不影响本体")
    void copy_hasIndependentBuffer() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        handler.insertItem(0, stack(Items.STICK, 10), false);

        LongInputItemHandler copy = (LongInputItemHandler) handler.copy();
        assertEquals(10, copy.getStackInSlot(0).getCount(), "副本应带上当前内容");
        copy.setStackInSlot(0, ItemStack.EMPTY);

        assertEquals(10L, buffer.getAmount(STICK), "写副本不得影响本体（配方检查期必须无副作用）");
        assertEquals(10, handler.getStackInSlot(0).getCount());
    }

    @Test
    @DisplayName("越界槽位与负数量都不产生副作用")
    void outOfRangeSlotsAndAmounts_areNoOps() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);

        assertEquals(handler.getSlots(), VISIBLE_SLOTS, "默认没有催化剂视图时槽位数固定为 200");
        assertEquals(2, handler.insertItem(-1, stack(Items.STICK, 2), false).getCount(), "负槽位应退还整堆");
        assertEquals(2, handler.insertItem(200, stack(Items.STICK, 2), false).getCount(), "越界槽位应退还整堆");
        assertTrue(handler.getStackInSlot(-1).isEmpty(), "负槽位应为空");
        assertTrue(handler.getStackInSlot(200).isEmpty(), "越界槽位应为空");
        assertTrue(handler.extractItem(0, 0, false).isEmpty(), "抽取 0 个应返回空堆");
        assertTrue(handler.extractItem(-1, 1, false).isEmpty());
        assertTrue(buffer.isEmpty(), "以上调用都不得改动缓冲");
    }

    @Test
    @DisplayName("催化剂展示槽只读：可见但不可插入/取出")
    void catalystView_isReadOnly() {
        LongItemBuffer buffer = new LongItemBuffer();
        LongInputItemHandler handler = handlerFor(buffer);
        ItemStack catalyst = stack(Items.COAL, 1);
        handler.setCatalystView(new ItemStack[]{catalyst});

        assertEquals(VISIBLE_SLOTS + 1, handler.getSlots(), "催化剂槽追加在可见槽位之后");
        assertEquals(1, handler.getStackInSlot(VISIBLE_SLOTS).getCount(), "催化剂槽应展示催化剂");

        assertEquals(3, handler.insertItem(VISIBLE_SLOTS, stack(Items.STICK, 3), false).getCount(),
                "催化剂槽拒绝插入");
        assertTrue(handler.extractItem(VISIBLE_SLOTS, 1, false).isEmpty(), "催化剂槽不可取出");
        handler.setStackInSlot(VISIBLE_SLOTS, stack(Items.COAL, 64));
        assertTrue(buffer.isEmpty(), "催化剂槽的读写都不得落到缓冲上");
        assertEquals(1, handler.getStackInSlot(VISIBLE_SLOTS).getCount(), "催化剂内容不得被改动");
    }
}

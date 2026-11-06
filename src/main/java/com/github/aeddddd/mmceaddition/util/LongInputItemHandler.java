package com.github.aeddddd.mmceaddition.util;

import hellfirepvp.modularmachinery.common.util.IItemHandlerImpl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 {@link LongItemBuffer} 包装为 {@link hellfirepvp.modularmachinery.common.util.IItemHandlerImpl}，
 * 供机器配方消耗输入。
 * <p>
 * 与 {@link LongBufferItemHandler}（输出仓）不同，这个 handler 允许 insert 和 extract，
 * 并且会把缓冲区中的物品变体映射到 200 个可见槽位上，使 MMCE 的配方检查/消耗能够正常工作。
 * <p>
 * <b>同步约定</b>：{@link LongItemBuffer} 的 synchronized 只保护它自己的 Map，
 * 保护不到本类的 {@link #slotVariants} 槽位映射。因此本类所有读写 slotVariants 与缓冲的方法
 * （含只读判定 {@link #wouldAcceptAll}、{@link #describeRejection} 与全有或全无写入 {@link #insertAll}）
 * 都在本实例锁上同步，锁序固定为 handler → buffer。
 * 反向的 buffer → handler 边不存在：{@link LongItemBuffer} 只在观察者回调里通知
 * {@code PatternAssemblySlot} 去打脏输出管理器，不会回调本 handler。
 */
public class LongInputItemHandler extends IItemHandlerImpl {

    private static final int VISIBLE_SLOTS = 200;

    /** 不做模拟占位时的空覆盖层，让实际写入与只读试算复用同一套槽位解析逻辑。 */
    private static final Map<Integer, ItemVariant> NO_OVERLAY = Collections.emptyMap();

    private final LongItemBuffer buffer;

    /**
     * 槽位到物品变体的映射。同一变体只能出现在一个槽位中。
     */
    private final ItemVariant[] slotVariants = new ItemVariant[VISIBLE_SLOTS];

    public LongInputItemHandler(LongItemBuffer buffer) {
        this.buffer = buffer;

        this.allowAnySlots = false;
        this.accessibleSides = new EnumFacing[0];
        this.slotLimits = new int[VISIBLE_SLOTS];
        this.inventory = new SlotStackHolder[VISIBLE_SLOTS];
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            this.slotLimits[i] = Integer.MAX_VALUE;
            this.inventory[i] = new SlotStackHolder(i);
            this.inventory[i].itemStack.set(ItemStack.EMPTY);
            this.slotVariants[i] = null;
        }
        this.inSlots = new int[VISIBLE_SLOTS];
        this.outSlots = new int[VISIBLE_SLOTS];
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            this.inSlots[i] = i;
            this.outSlots[i] = i;
        }
        this.miscSlots = new int[0];
    }

    /**
     * 仅供检查副本使用的催化剂只读视图：下标从 {@link #VISIBLE_SLOTS} 起。
     * 让 RequirementCatalyst 在基于副本的配方检查/并行度计算中能看到催化剂存在；
     * 真实 handler 不设置此视图，催化剂永远不会被消耗或插入。
     */
    private ItemStack[] catalystView = null;

    private LongInputItemHandler(LongItemBuffer buffer, ItemVariant[] slotVariants) {
        this(buffer);
        System.arraycopy(slotVariants, 0, this.slotVariants, 0, Math.min(slotVariants.length, VISIBLE_SLOTS));
    }

    /** 为检查副本追加只读的催化剂展示槽（每个元素已是对外的拷贝）。 */
    public void setCatalystView(ItemStack[] catalysts) {
        this.catalystView = catalysts;
    }

    private int catalystSlots() {
        return catalystView == null ? 0 : catalystView.length;
    }

    @Override
    public int getSlots() {
        return VISIBLE_SLOTS + catalystSlots();
    }

    @Nonnull
    @Override
    public synchronized ItemStack getStackInSlot(int slot) {
        if (slot >= VISIBLE_SLOTS) {
            int idx = slot - VISIBLE_SLOTS;
            return idx >= 0 && idx < catalystSlots() ? catalystView[idx].copy() : ItemStack.EMPTY;
        }
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemVariant variant = slotVariants[slot];
        if (variant == null) {
            return ItemStack.EMPTY;
        }
        long amount = buffer.getAmount(variant);
        if (amount <= 0) {
            slotVariants[slot] = null;
            return ItemStack.EMPTY;
        }
        ItemStack stack = variant.toSingleStack();
        stack.setCount((int) Math.min(amount, Integer.MAX_VALUE));
        return stack;
    }

    @Nonnull
    @Override
    public synchronized ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot < 0 || slot >= VISIBLE_SLOTS + catalystSlots()) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        }
        // 催化剂展示槽拒绝一切插入
        if (slot >= VISIBLE_SLOTS) {
            return stack.copy();
        }

        ItemVariant variant = new ItemVariant(stack);

        // 找到该变体已有的槽位，优先合并。
        int targetSlot = findSlotWithVariant(variant, NO_OVERLAY);
        if (targetSlot < 0) {
            // 否则使用请求的槽位，如果它为空。
            if (slotVariants[slot] == null) {
                targetSlot = slot;
            } else {
                // 查找任意空槽。
                targetSlot = findEmptySlot(NO_OVERLAY);
            }
        }
        if (targetSlot < 0) {
            return stack.copy();
        }

        ItemVariant existingVariant = slotVariants[targetSlot];
        long current = existingVariant == null ? 0 : buffer.getAmount(existingVariant);
        long space = Long.MAX_VALUE - current;
        int count = stack.getCount();
        if (space <= 0) {
            return stack.copy();
        }

        if (simulate) {
            if (space >= count) {
                return ItemStack.EMPTY;
            }
            ItemStack remainder = stack.copy();
            remainder.setCount((int) (count - space));
            return remainder;
        }

        ItemStack remainder = buffer.insert(stack);
        if (slotVariants[targetSlot] == null || !slotVariants[targetSlot].equals(variant)) {
            slotVariants[targetSlot] = variant;
        }
        return remainder;
    }

    @Nonnull
    @Override
    public synchronized ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot < 0 || slot >= VISIBLE_SLOTS + catalystSlots()) {
            return ItemStack.EMPTY;
        }
        // 催化剂展示槽不可取出
        if (slot >= VISIBLE_SLOTS) {
            return ItemStack.EMPTY;
        }
        ItemVariant variant = slotVariants[slot];
        if (variant == null) {
            return ItemStack.EMPTY;
        }
        long available = buffer.getAmount(variant);
        if (available <= 0) {
            slotVariants[slot] = null;
            return ItemStack.EMPTY;
        }
        int toExtract = (int) Math.min(amount, Math.min(available, Integer.MAX_VALUE));
        if (!simulate) {
            buffer.extract(variant, toExtract);
            if (buffer.getAmount(variant) <= 0) {
                slotVariants[slot] = null;
            }
        }
        ItemStack result = variant.toSingleStack();
        result.setCount(toExtract);
        return result;
    }

    @Override
    public synchronized void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (slot < 0 || slot >= VISIBLE_SLOTS + catalystSlots()) {
            return;
        }
        // 催化剂展示槽只读：MMCE 检查期对副本的 setStackInSlot 落点不影响催化剂
        if (slot >= VISIBLE_SLOTS) {
            return;
        }
        // setStackInSlot 是"替换该槽内容"语义，MMCE 的 ItemUtils.consumeAll/insertAll
        // 不走 extractItem/insertItem，而是以它作为消耗与插入的最终落点：
        //   - 完全消耗 → setStackInSlot(slot, EMPTY)
        //   - 部分消耗 → setStackInSlot(slot, 可见数量 - 消耗数)
        // 因此这里必须按"目标数量"与"当前数量"的差额调整缓冲区，
        // 否则消耗永远是空操作（材料不减少）或重复插入（刷物品）。
        // 注意：MMCE 看到的数量经 getStackInSlot 被钳制到 Integer.MAX_VALUE，
        // 差额必须相对这个"可见数量"计算；若直接对比缓冲区的原始 long 数量，
        // 缓冲超过 int 上限（约 2.1G）时，一次消耗会把超出上限的部分全部吞掉。
        ItemVariant oldVariant = slotVariants[slot];
        long oldAmount = oldVariant == null ? 0 : buffer.getAmount(oldVariant);
        long visibleOld = Math.min(oldAmount, Integer.MAX_VALUE);

        if (stack.isEmpty()) {
            // 替换为空：MMCE 把它看到的部分消耗殆尽，只扣除可见数量；
            // 缓冲区里超出 int 上限的隐藏余量必须保留。
            if (oldVariant != null && visibleOld > 0) {
                buffer.extract(oldVariant, visibleOld);
            }
            // 隐藏余量还在时保留槽位映射，让 getStackInSlot 继续暴露剩余部分。
            if (oldVariant == null || buffer.getAmount(oldVariant) <= 0) {
                slotVariants[slot] = null;
            }
            return;
        }

        ItemVariant newVariant = new ItemVariant(stack);
        long newCount = stack.getCount();
        if (newVariant.equals(oldVariant)) {
            // 同变体替换：按与可见数量的差额调整（newCount 是 int，delta 不会溢出）。
            long delta = newCount - visibleOld;
            if (delta > 0) {
                ItemStack add = stack.copy();
                add.setCount((int) delta);
                buffer.insert(add);
            } else if (delta < 0) {
                buffer.extract(oldVariant, -delta);
            }
            if (buffer.getAmount(newVariant) <= 0) {
                slotVariants[slot] = null;
            }
        } else {
            // 不同变体：MMCE 把槽内容替换为另一种物品，只扣除它看到的可见部分。
            if (oldVariant != null && visibleOld > 0) {
                buffer.extract(oldVariant, visibleOld);
            }
            // 维持"同一变体只映射一个槽"的不变式，避免双份显示。
            for (int i = 0; i < VISIBLE_SLOTS; i++) {
                if (newVariant.equals(slotVariants[i])) {
                    slotVariants[i] = null;
                }
            }
            buffer.insert(stack);
            slotVariants[slot] = newVariant;
            // 旧变体还有隐藏余量时迁移到空闲槽位继续暴露，避免变成不可见的死库存。
            if (oldVariant != null && buffer.getAmount(oldVariant) > 0) {
                for (int i = 0; i < VISIBLE_SLOTS; i++) {
                    if (slotVariants[i] == null) {
                        slotVariants[i] = oldVariant;
                        break;
                    }
                }
            }
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    /**
     * 从缓冲区抽取指定变体的指定数量。
     *
     * @param variant 物品变体
     * @param amount  要抽取的数量
     * @return 实际抽取数量
     */
    public long extract(@Nonnull ItemVariant variant, long amount) {
        return buffer.extract(variant, amount);
    }

    /**
     * 获取缓冲区中指定变体的当前数量。
     */
    public long getAmount(@Nonnull ItemVariant variant) {
        return buffer.getAmount(variant);
    }

    @Override
    public IItemHandlerImpl copy() {
        LongItemBuffer copiedBuffer = new LongItemBuffer();
        copiedBuffer.readFromNBT(bufferToNbt());
        ItemVariant[] copiedVariants = rebuildSlotVariants(copiedBuffer);
        LongInputItemHandler copied = new LongInputItemHandler(copiedBuffer, copiedVariants);
        // 副本的副本保留催化剂视图（数组内 ItemStack 不会被修改，浅拷贝即可）
        if (catalystView != null) {
            copied.setCatalystView(catalystView.clone());
        }
        return copied;
    }

    @Override
    public IItemHandlerImpl fastCopy() {
        return copy();
    }

    @Nonnull
    @Override
    public net.minecraftforge.items.IItemHandlerModifiable asGUIAccess() {
        return this;
    }

    /** 查找映射了给定变体的槽位；{@code overlay} 中的模拟占位优先于真实映射。 */
    private int findSlotWithVariant(ItemVariant variant, @Nonnull Map<Integer, ItemVariant> overlay) {
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            if (variant.equals(effectiveVariant(overlay, i))) {
                return i;
            }
        }
        return -1;
    }

    private int findEmptySlot(@Nonnull Map<Integer, ItemVariant> overlay) {
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            if (effectiveVariant(overlay, i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 槽位的有效变体：只读试算时 {@code overlay} 里模拟占用的槽位优先于真实映射，
     * 实际写入时传 {@link #NO_OVERLAY}，退化为直接读 {@link #slotVariants}。
     */
    private ItemVariant effectiveVariant(@Nonnull Map<Integer, ItemVariant> overlay, int slot) {
        ItemVariant claimed = overlay.get(slot);
        return claimed != null ? claimed : slotVariants[slot];
    }

    /**
     * 按 {@link #insertItem(int, ItemStack, boolean)} 的顺序语义做只读试算。
     * <p>
     * 试算必须自己跟踪"本次将占用的空槽"：{@code insertItem} 的 simulate 分支并不写
     * {@link #slotVariants}，若逐个模拟，多个新变体会抢到同一个空槽而误报可行。
     * 调用方必须已持有本实例锁。
     */
    @Nonnull
    private FitResult simulate(@Nonnull ItemStack[] stacks) {
        Map<Integer, ItemVariant> claimedSlots = new HashMap<>();
        Map<ItemVariant, Long> claimedAmounts = new HashMap<>();

        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemVariant variant = new ItemVariant(stack);

            int targetSlot = findSlotWithVariant(variant, claimedSlots);
            if (targetSlot < 0) {
                // 与 insertItem 一致：优先请求的槽位 0（pushPattern 总是写 0 号槽），否则找任意空槽。
                targetSlot = effectiveVariant(claimedSlots, 0) == null ? 0 : findEmptySlot(claimedSlots);
            }
            if (targetSlot < 0) {
                // 200 个可见槽位都被其他变体占满：新变体无处落位，insertItem 会整堆拒收。
                return new FitResult(i, 0, true);
            }

            long current = buffer.getAmount(variant) + claimedAmounts.getOrDefault(variant, 0L);
            long space = Long.MAX_VALUE - current;
            if (space < stack.getCount()) {
                return new FitResult(i, space, false);
            }
            claimedSlots.put(targetSlot, variant);
            claimedAmounts.put(variant, claimedAmounts.getOrDefault(variant, 0L) + stack.getCount());
        }
        return FitResult.OK;
    }

    /**
     * 只读判定：把给定的一组物品堆全部写入本 handler 是否会完整成功。
     * <p>
     * 不修改任何状态、不触发观察者，可用于写入前的 all-or-nothing 判定。容量上限有两处：
     * <ul>
     *   <li>单变体数量受 {@link LongItemBuffer} 的 Long 上限约束（见 {@link #insertItem} 的 space 判断）；</li>
     *   <li>新变体受 {@value #VISIBLE_SLOTS} 个可见槽位约束：
     *       {@link #findEmptySlot} 返回 -1 时 {@link #insertItem} 会返回整堆剩余量。</li>
     * </ul>
     *
     * @param stacks 待写入的物品堆（可含空堆）
     * @return true 表示全部可写入；false 表示会被整堆或部分拒收
     */
    public synchronized boolean wouldAcceptAll(@Nonnull ItemStack[] stacks) {
        return simulate(stacks).accepted;
    }

    /**
     * 只读诊断：说明 {@link #wouldAcceptAll} 为何拒收，仅供 debug 日志使用。
     *
     * @return 拒收原因；可完整写入时返回 null
     */
    @Nullable
    public synchronized String describeRejection(@Nonnull ItemStack[] stacks) {
        FitResult result = simulate(stacks);
        if (result.accepted) {
            return null;
        }
        ItemStack rejected = stacks[result.rejectedIndex];
        if (result.noFreeSlot) {
            return String.format("变体 %s 无处落位：%d 个可见槽位已被其他变体占满",
                    new ItemVariant(rejected), VISIBLE_SLOTS);
        }
        return String.format("变体 %s 数量已达 Long 上限：剩余容量 %d，本次需要 %d",
                new ItemVariant(rejected), result.freeCapacity, rejected.getCount());
    }

    /**
     * 全有或全无写入：先只读试算，不可行时直接返回 false 且不修改任何状态；
     * 试算通过后再逐堆实际写入（与 {@link #insertItem} 同一路径，保证槽位映射一致）。
     * 试算与写入在同一把锁内完成，因此不会留下部分写入的半成品状态。
     *
     * @return true 表示全部写入成功；false 表示一个都没有写入
     */
    public synchronized boolean insertAll(@Nonnull ItemStack[] stacks) {
        if (!simulate(stacks).accepted) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!insertItem(0, stack, false).isEmpty()) {
                // 试算通过后仍被拒：同一把锁内状态不会被改动，理论不可达；
                // 这里如实上报失败，把订单留给 CPU 下次重试。
                return false;
            }
        }
        return true;
    }

    /**
     * 只读试算结果：{@code accepted} 为 false 时，{@code noFreeSlot} 表示因没有空闲可见槽位而拒收，
     * 否则 {@code freeCapacity} 是该变体的剩余可容纳数量。
     */
    private static final class FitResult {

        private static final FitResult OK = new FitResult(-1, 0, false);

        private final int rejectedIndex;
        private final long freeCapacity;
        private final boolean noFreeSlot;
        private final boolean accepted;

        private FitResult(int rejectedIndex, long freeCapacity, boolean noFreeSlot) {
            this.rejectedIndex = rejectedIndex;
            this.freeCapacity = freeCapacity;
            this.noFreeSlot = noFreeSlot;
            this.accepted = rejectedIndex < 0;
        }
    }

    /**
     * 根据当前缓冲区内容重建槽位到物品变体的映射。
     * <p>
     * 当缓冲区被直接修改（例如从 NBT 加载、或被外部代码直接写入）后，
     * 需要调用此方法让 {@link #getStackInSlot} 能够正确暴露物品。
     */
    public synchronized void syncWithBuffer() {
        int index = 0;
        java.util.Arrays.fill(slotVariants, null);
        for (Map.Entry<ItemVariant, Long> entry : buffer.snapshot().entrySet()) {
            if (entry.getValue() > 0 && index < VISIBLE_SLOTS) {
                slotVariants[index++] = entry.getKey();
            }
        }
    }

    private ItemVariant[] rebuildSlotVariants(LongItemBuffer targetBuffer) {
        ItemVariant[] variants = new ItemVariant[VISIBLE_SLOTS];
        int index = 0;
        for (Map.Entry<ItemVariant, Long> entry : targetBuffer.snapshot().entrySet()) {
            if (entry.getValue() > 0 && index < VISIBLE_SLOTS) {
                variants[index++] = entry.getKey();
            }
        }
        return variants;
    }

    private net.minecraft.nbt.NBTTagCompound bufferToNbt() {
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        buffer.writeToNBT(tag);
        return tag;
    }
}

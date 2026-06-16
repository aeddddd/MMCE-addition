package com.github.aeddddd.mmceaddition.tile;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.me.helpers.MachineSource;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager;
import com.github.aeddddd.mmceaddition.util.IBufferObserver;
import com.github.aeddddd.mmceaddition.util.LongBufferItemHandler;
import com.github.aeddddd.mmceaddition.util.LongItemBuffer;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import hellfirepvp.modularmachinery.common.tiles.base.MachineComponentTile;
import hellfirepvp.modularmachinery.common.tiles.base.TileColorableMachineComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ME 异步物品输出总线 TileEntity。
 * <p>
 * 这个 TileEntity 是异步输出的核心：
 * <ul>
 *   <li>通过 {@link MachineComponentTile#provideComponent()} 向 MMCE 机器暴露一个物品输出总线</li>
 *   <li>机器产出先进入 {@link LongItemBuffer} 缓冲，而不是立刻注入 ME 网络</li>
 *   <li>持有 AE2 的 {@link AENetworkProxy}，用于批量注入时与 ME 网格交互</li>
 *   <li>通过 {@link IBufferObserver} 在缓冲区非空时通知 {@link MEAsyncOutputManager}</li>
 * </ul>
 */
public class TileMEAsyncItemOutputBus extends TileColorableMachineComponent
        implements MachineComponentTile, IActionHost, IGridProxyable, IBufferObserver {

    /**
     * 物品缓冲区。支持 Long 数量上限，可以累积远超普通槽位的产出。
     * 传入 this 作为观察者，缓冲区从空变非空时会回调 onBufferNonEmpty()。
     */
    private final LongItemBuffer itemBuffer = new LongItemBuffer(this);

    /**
     * 物品处理器包装。把 LongItemBuffer 包装成 MMCE 能识别的物品总线接口。
     */
    private final LongBufferItemHandler itemHandler = new LongBufferItemHandler(itemBuffer);

    /**
     * AE2 网络代理。负责与 AE 网格建立/断开连接、获取 grid、执行安全校验等。
     * 构造函数参数：宿主、名称、代表物品、是否需要通道。
     */
    private final AENetworkProxy proxy;

    /**
     * AE2 动作源。每次向 ME 网络注入时都需要一个 IActionSource，用于权限/审计。
     */
    private final IActionSource source;

    /**
     * 是否已经注册到 MEAsyncOutputManager。避免重复注册。
     */
    private boolean registered = false;

    public TileMEAsyncItemOutputBus() {
        // 代表物品用于 AE2 在网格中显示该节点的“机器物品”。
        this.proxy = new AENetworkProxy(this, "aeProxy", new ItemStack(RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS), true);
        this.proxy.setIdlePowerUsage(1.0);
        // REQUIRE_CHANNEL 表示该节点需要 AE 频道才能工作（类似普通 ME 设备）。
        this.proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        this.source = new MachineSource(this);
    }

    /**
     * 向 MMCE 机器提供组件能力。
     * <p>
     * MMCE 在构建机器结构时会扫描所有 MachineComponentTile，并调用此方法获取
     * 对应的 MachineComponent。这里返回一个 OUTPUT 类型的 ItemBus，
     * 其内部容器就是我们的 LongBufferItemHandler。
     *
     * @return 物品输出总线组件
     */
    @Nullable
    @Override
    public MachineComponent.ItemBus provideComponent() {
        return new MachineComponent.ItemBus(IOType.OUTPUT) {
            @Nonnull
            @Override
            public IItemHandlerModifiable getContainerProvider() {
                return itemHandler;
            }
        };
    }

    /**
     * @return 内部 LongItemBuffer，供管理器读取和注入
     */
    public LongItemBuffer getItemBuffer() {
        return itemBuffer;
    }

    /**
     * @return 当前缓冲区内所有物品的总数量
     */
    public long getBufferedAmount() {
        return itemBuffer.getTotalAmount();
    }

    /**
     * 获取 AE2 网络代理。
     */
    @Nonnull
    @Override
    public AENetworkProxy getProxy() {
        return proxy;
    }

    @Nullable
    @Override
    public IGridNode getGridNode(@Nonnull AEPartLocation dir) {
        return proxy.getNode();
    }

    @Nonnull
    @Override
    public AECableType getCableConnectionType(@Nonnull AEPartLocation dir) {
        return AECableType.SMART;
    }

    @Nonnull
    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public void gridChanged() {
    }

    @Nonnull
    @Override
    public IGridNode getActionableNode() {
        return proxy.getNode();
    }

    /**
     * 安全破坏：当 AE2 安全系统触发时销毁该方块。
     */
    @Override
    public void securityBreak() {
        if (world != null && pos != null) {
            world.setBlockToAir(pos);
        }
    }

    /**
     * TileEntity 被验证时调用。通知 AE 代理验证。
     */
    @Override
    public void validate() {
        super.validate();
        proxy.validate();
    }

    /**
     * TileEntity 被加载到世界时调用。
     * <p>
     * 这里做两件事：
     * <ul>
     *   <li>通知 AE 代理准备就绪</li>
     *   <li>在服务端把本 TileEntity 注册到 MEAsyncOutputManager</li>
     * </ul>
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (!world.isRemote) {
            proxy.onReady();
            if (!registered) {
                MEAsyncOutputManager.INSTANCE.register(this);
                registered = true;
            }
        }
    }

    /**
     * TileEntity 失效时调用（例如方块被破坏）。
     * 注销 AE 代理和管理器注册。
     */
    @Override
    public void invalidate() {
        super.invalidate();
        proxy.invalidate();
        unregisterManager();
    }

    /**
     * TileEntity 所在区块卸载时调用。
     * 注销 AE 代理和管理器注册。
     */
    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        proxy.onChunkUnload();
        unregisterManager();
    }

    /**
     * 从管理器注销本 TileEntity。
     */
    private void unregisterManager() {
        if (registered) {
            MEAsyncOutputManager.INSTANCE.unregister(this);
            registered = false;
        }
    }

    /**
     * @return AE2 动作源，批量注入时使用
     */
    public IActionSource getSource() {
        return source;
    }

    /**
     * 声明本 TileEntity 支持物品处理能力（Capability）。
     */
    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    /**
     * 返回物品处理能力实例。
     */
    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(itemHandler);
        }
        return super.getCapability(capability, facing);
    }

    /**
     * 读取自定义 NBT。
     * <p>
     * TileColorableMachineComponent 已经处理了颜色等基础数据，这里只需要：
     * <ul>
     *   <li>恢复 AE 代理状态</li>
     *   <li>恢复物品缓冲区内容</li>
     * </ul>
     * <p>
     * 注意：此时 world 字段可能尚未赋值，因此不能调用 world.isRemote。
     */
    @Override
    public void readCustomNBT(NBTTagCompound compound) {
        super.readCustomNBT(compound);
        try {
            proxy.readFromNBT(compound);
        } catch (IllegalStateException e) {
            // 忽略非法状态异常，通常发生在代理尚未初始化时
        }
        if (compound.hasKey("Buffer")) {
            itemBuffer.readFromNBT(compound.getCompoundTag("Buffer"));
        }
    }

    /**
     * 写入自定义 NBT。
     */
    @Override
    public void writeCustomNBT(NBTTagCompound compound) {
        super.writeCustomNBT(compound);
        proxy.writeToNBT(compound);
        NBTTagCompound bufferTag = new NBTTagCompound();
        itemBuffer.writeToNBT(bufferTag);
        compound.setTag("Buffer", bufferTag);
    }

    /**
     * 把缓冲区内容写入独立的 NBT（用于方块掉落）。
     */
    public void writeBufferToNBT(NBTTagCompound compound) {
        itemBuffer.writeToNBT(compound);
    }

    /**
     * 从独立 NBT 恢复缓冲区内容（用于方块放置）。
     */
    public void readBufferFromNBT(NBTTagCompound compound) {
        itemBuffer.readFromNBT(compound);
    }

    /**
     * 缓冲区观察者回调。
     * <p>
     * 当 LongItemBuffer 从空变为非空时调用，把本 TileEntity 标记为“待处理”。
     */
    @Override
    public void onBufferNonEmpty() {
        if (world != null && !world.isRemote) {
            MEAsyncOutputManager.INSTANCE.markDirty(this);
        }
    }
}

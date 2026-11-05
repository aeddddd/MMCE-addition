package com.github.aeddddd.mmceaddition.tile.provider;

import ae2.api.networking.security.IActionHost;
import com.github.aeddddd.mmceaddition.compat.NetworkResourceCompat;
import hellfirepvp.modularmachinery.common.machine.IOType;
import hellfirepvp.modularmachinery.common.machine.MachineComponent;
import kport.modularmagic.common.tile.TileManaProvider;
import kport.modularmagic.common.tile.machinecomponent.MachineComponentManaProvider;

/**
 * 网络魔力代理 provider（不放置进世界的虚拟 Tile）.
 *
 * <p>继承 MMCE 原生 {@link TileManaProvider} 并把存量/消耗委托给 AE 网络
 * （经 {@link NetworkResourceCompat} 通过原生 AE2S 资源键访问网络 Mana）。
 * ManaProviderCopy 的快照与 RequirementMana 的消耗全部通过可重写方法走通，
 * 无需 mixin。</p>
 *
 * <p><b>条件加载</b>：类声明硬引用 Botania 链路（TileManaProvider implements IManaReceiver），
 * 仅在 Botania 在场时由 ManaComponentFactory 实例化。</p>
 *
 * <p>语义说明：网络存量按 int 截断暴露（MMCE 魔力需求为 int 量级）；
 * {@link #reduceMana} 为全有或全无（先模拟再实际扣除），避免部分扣除后配方仍失败导致魔力丢失。</p>
 */
public class NetworkManaProvider extends TileManaProvider {

    private final IActionHost host;

    public NetworkManaProvider(IActionHost host) {
        this.host = host;
    }

    @Override
    public int getCurrentMana() {
        return (int) Math.min(NetworkResourceCompat.getStoredMana(host), Integer.MAX_VALUE);
    }

    @Override
    public synchronized void reduceMana(int amount) {
        if (amount <= 0) {
            return;
        }
        // 全有或全无：先模拟确认存量充足再实际扣除
        if (NetworkResourceCompat.extractMana(host, amount, true) >= amount) {
            NetworkResourceCompat.extractMana(host, amount, false);
        }
    }

    @Override
    public synchronized void recieveMana(int amount) {
        // INPUT 专用代理：不接收魔力（OUTPUT 需求不在适配范围内）
    }

    @Override
    public int getManaCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isFull() {
        return false;
    }

    @Override
    public void update() {
        // 虚拟 Tile 不加入世界，不会 tick；防御性覆盖
    }

    @Override
    public MachineComponent<?> provideComponent() {
        // 虚拟 Tile 不参与结构扫描；组件由 ManaComponentFactory 直接构造，此处仅为契约完整性
        return new MachineComponentManaProvider(IOType.INPUT, this);
    }
}

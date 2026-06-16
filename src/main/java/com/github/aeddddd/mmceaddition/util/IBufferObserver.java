package com.github.aeddddd.mmceaddition.util;

/**
 * 缓冲区观察者接口。
 * <p>
 * 这是一个简单的观察者回调，用于解耦缓冲区和管理器：
 * 缓冲区不知道管理器的存在，只需要在“从空变非空”时通知外部；
 * TileEntity 实现这个接口，再把自己传给缓冲区，收到回调后通知 {@link com.github.aeddddd.mmceaddition.manager.MEAsyncOutputManager}。
 */
public interface IBufferObserver {

    /**
     * 当缓冲区从空状态变为非空状态时调用。
     * 实现方应把对应方块加入待处理集合。
     */
    void onBufferNonEmpty();
}

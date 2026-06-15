package com.github.aeddddd.mmceaddition.util;

/**
 * 缓冲区从空变为非空时的观察者回调。
 */
public interface IBufferObserver {

    /**
     * 当缓冲区从空状态变为非空状态时调用。
     * 实现方应把对应方块加入待处理集合。
     */
    void onBufferNonEmpty();
}

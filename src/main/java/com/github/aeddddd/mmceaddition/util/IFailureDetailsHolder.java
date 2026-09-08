package com.github.aeddddd.mmceaddition.util;

import java.util.List;

/**
 * 挂接到 MMCE 机器控制器上的“配方失败详情”持有者接口。
 * <p>
 * 由 {@code FailureDetailsControllerMixin} 实现。MMCE 原生的 {@code CraftingStatus}
 * 只保留最佳匹配配方的第一条错误消息，本接口在控制器上缓存完整的错误列表
 * （未本地化键或 CrT 自定义原文），供 TOP 探针与控制器 GUI 展示。
 * 列表为不可变副本，通过 volatile 引用发布，异步搜索线程写入、其他线程读取均安全。
 */
public interface IFailureDetailsHolder {

    /**
     * 获取当前缓存的失败详情列表（可能为空，不会为 null）。
     */
    List<String> mmceaddition$getFailureDetails();

    /**
     * 设置失败详情列表。传入空列表表示清除。
     */
    void mmceaddition$setFailureDetails(List<String> details);
}

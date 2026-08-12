package com.github.aeddddd.mmceaddition.parallel;

/**
 * 挂接到 MMCE 机器控制器上的“迁移并行度”持有者接口。
 * <p>
 * 由 {@code ParallelismControllerMixin} 实现，用于在控制器实例上缓存
 * 当前结构实际生效的并行度（由伪并行迁移器根据已安装的结构元件计算）。
 * 初始值为 -1，表示尚未计算（首次查询时惰性计算）。
 */
public interface IMigratedParallelismHolder {

    /**
     * 获取当前缓存的迁移并行度。-1 表示未初始化，1 表示无并行。
     */
    int mmceaddition$getMigratedParallelism();

    /**
     * 设置迁移并行度。通常在结构成型/更新事件中写入。
     */
    void mmceaddition$setMigratedParallelism(int value);
}

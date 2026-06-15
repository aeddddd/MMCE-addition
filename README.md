# MMCE Addition

一个面向 Minecraft 1.12.2 的 ModularMachinery Community Edition（MMCE）附属模组，
为 AE2 Unofficial Extended Life（AE2UEL）提供**异步 ME 输出总成**。

## 功能

- **ME 异步物品输出总线** (`me_async_item_output_bus`)
- **ME 异步流体输出仓** (`me_async_fluid_output_hatch`)
- 内部缓存支持 `Long` 数量上限
- 按 AE 网格批量注入，避免每个方块都注册为 `IGridTickable`
- 在大规模工厂（数千台 MMCE 多方块）下显著降低 AE 网格 tick 开销
- Mixin 兼容原 MMCE ME 输出总线结构位置，默认启用，可在配置中关闭

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/`。

## 依赖

- Minecraft 1.12.2
- Forge 14.23.5.2847
- ModularMachinery Community Edition
- AE2 Unofficial Extended Life

## 授权

待定

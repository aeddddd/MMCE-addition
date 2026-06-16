# MMCE Addition

一个用于教学目的的 Minecraft 1.12.2 Forge 模组示例项目。

本模组为 [ModularMachinery-CE](https://github.com/KasumiNova/ModularMachinery-CE) 添加了异步 ME 物品输出总线和流体输出仓，
演示了如何从零开始构建一个完整的 Forge 模组：注册方块/物品/TileEntity、使用 Mixin、接入本地依赖、处理渲染、添加配置和命令等。

## 技术栈

- Minecraft 1.12.2
- Forge 14.23.5.2847
- ForgeGradle 2.3
- MCP mappings: stable_39
- Gradle 4.10.3
- Mixin 0.8.7（通过 MixinBooter 加载）

## 本模组做了什么

1. **异步 ME 物品输出总线**：机器产出先进入 Long 上限缓冲区，再按配置间隔批量注入 ME 网络。
2. **异步 ME 流体输出仓**：同上，针对流体。
3. **位置兼容（Mixin）**：无需修改机器 JSON，异步版本可直接替换原版 MMCE 输出仓位置。
4. **管理命令**：一键把已加载区块中的原版 ME 物品输出仓替换为异步版本，方便性能对比测试。

## 项目结构

```
src/main/java/com/github/aeddddd/mmceaddition/
├── MMCEAddition.java              # 模组主类
├── MMCEAdditionCreativeTab.java   # 创造模式物品栏
├── RegistryHandler.java           # 方块/物品/TileEntity 注册
├── config/
│   └── MMCEAdditionConfig.java    # Forge 配置
├── proxy/
│   ├── CommonProxy.java           # 服务端/公共代理
│   └── ClientProxy.java           # 客户端代理
├── block/
│   ├── BlockMEAsyncItemOutputBus.java
│   └── BlockMEAsyncFluidOutputHatch.java
├── tile/
│   ├── TileMEAsyncItemOutputBus.java
│   └── TileMEAsyncFluidOutputHatch.java
├── util/
│   ├── IBufferObserver.java       # 缓冲区观察者接口
│   ├── ItemVariant.java           # 物品变体（Item + meta + NBT）
│   ├── LongItemBuffer.java        # Long 上限物品缓冲
│   ├── LongFluidBuffer.java       # Long 上限流体缓冲
│   ├── LongBufferItemHandler.java # 把缓冲包装为 IItemHandlerModifiable
│   └── LongBufferFluidHandler.java# 把缓冲包装为 IFluidHandler
├── manager/
│   └── MEAsyncOutputManager.java  # 统一调度注入
├── client/
│   └── ClientEventHandler.java    # 模型注册、Tooltip
├── command/
│   └── CommandMMCEAddition.java   # 管理命令
└── mixin/
    └── BlockInformationMixin.java # 结构匹配兼容
```

## 关键概念讲解

### 1. @Mod 主类

`MMCEAddition.java` 是 Forge 加载模组的入口。

- `modid`：模组唯一标识，决定资源路径、NBT 前缀等。
- `@SidedProxy`：根据客户端/服务端自动选择 `ClientProxy` 或 `CommonProxy`。
- `@Mod.EventHandler`：接收 Forge 生命周期事件（preInit/init/postInit/serverStarting）。
- `ILateMixinLoader`：MixinBooter 接口，返回 Mixin 配置文件。

### 2. 注册

Forge 1.12.2 使用事件驱动注册：

- `RegistryEvent.Register<Block>`：注册方块。
- `RegistryEvent.Register<Item>`：注册物品（包括 ItemBlock）。
- `GameRegistry.registerTileEntity(...)`：注册 TileEntity。

`RegistryHandler` 订阅了这两个事件。

### 3. Sided Proxy

客户端代码（如 `net.minecraft.client.*`）不能直接出现在服务端，否则会 `ClassNotFoundException`。
通过 `CommonProxy` + `ClientProxy` 分离：

- `CommonProxy`：两端都执行。
- `ClientProxy extends CommonProxy`：只在客户端执行，可安全引用客户端类。

### 4. TileEntity

方块本身只保存状态和渲染信息；复杂逻辑（如网络连接、缓冲、GUI）放在 TileEntity 中。

- `validate()` / `invalidate()` / `onChunkUnload()`：生命周期钩子，常用于注册/注销网络/管理器。
- `readCustomNBT()` / `writeCustomNBT()`：MMCE 基类提供的自定义 NBT 读写。
- `provideComponent()`：`MachineComponentTile` 接口要求，向 MMCE 机器暴露组件能力。

### 5. Capability

Forge 的 Capability 系统允许 TileEntity 暴露能力接口：

- `CapabilityItemHandler.ITEM_HANDLER_CAPABILITY`：物品处理能力。
- `CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY`：流体处理能力。

机器配方输出时会查询这些能力并把产物插入。

### 6. 本地依赖与 deobfCompile

MMCE 和 AE2UEL 是本地 JAR，放在 `libs/` 目录下：

```gradle
dependencies {
    deobfCompile name: 'ModularMachinery-CE-2.2.2'
    deobfCompile name: 'ae2-uel-v0.56.7'
}
```

`deobfCompile` 是 ForgeGradle 提供的配置，会自动把混淆的 JAR 反混淆为开发环境可读的方法名。

### 7. Mixin

`BlockInformationMixin` 使用 Mixin 修改 MMCE 的 `BlockArray.BlockInformation.matchesState`，
让异步版本方块被 MMCE 结构匹配逻辑识别为原版输出仓的合法替代。

关键注解：

- `@Mixin(value = TargetClass.class, remap = false)`：指定要注入的目标类。
- `@Inject(method = "targetMethod", at = @At("HEAD"), cancellable = true)`：在目标方法开头注入，可取消原方法执行。

### 8. 渲染

方块渲染需要：

1. `blockstates/*.json`：把方块状态映射到模型。
2. `models/block/*.json`：方块模型。
3. `models/item/*.json`：物品模型（通常继承方块模型或 item/generated）。

`BlockContainer` 默认返回 `ENTITYBLOCK_ANIMATED` 渲染类型，会导致方块透明，
因此必须覆盖 `getRenderType()` 返回 `EnumBlockRenderType.MODEL`。

### 9. 异步注入管理器

`MEAsyncOutputManager` 是单例，通过 `ServerTickEvent` 每 N tick 批量把缓冲区内容注入 ME 网络。

核心优化点：

- 不每个方块都注册 AE2 `IGridTickable`。
- 只处理缓冲区非空的“脏”方块。
- 按 AE 网格分组，每个网格每 tick 只查一次 storage/energy。
- 配置 `injectionInterval` 控制注入频率，降低 ME 网格压力。

## 如何构建

```bash
./gradlew build
```

构建产物在 `build/libs/MMCE-addition-1.0-SNAPSHOT.jar`。

## 如何运行客户端

```bash
./gradlew runClient
```

## 如何运行测试

```bash
./gradlew test
```

## 如何扩展：添加一个新的输出总线

1. 在 `block/` 下新建方块类，继承 `BlockMachineComponent`。
2. 在 `tile/` 下新建 TileEntity，继承 `TileColorableMachineComponent` 并实现 `MachineComponentTile`。
3. 在 `RegistryHandler` 注册方块、ItemBlock、TileEntity。
4. 在 `assets/mmceaddition/blockstates/` 和 `assets/mmceaddition/models/` 添加模型。
5. 在 `assets/mmceaddition/lang/` 添加语言条目。
6. 如需接入 AE2，参考 `TileMEAsyncItemOutputBus` 创建 `AENetworkProxy`。
7. 如需批量调度，把 TileEntity 注册到 `MEAsyncOutputManager`。

## 常见坑

1. **方块透明**：`BlockContainer` 默认渲染类型不是 MODEL，需要覆盖 `getRenderType()`。
2. **物品紫黑块**：检查 `models/item/*.json` 路径、模型父类、纹理路径；确保 `ModelLoader.setCustomModelResourceLocation` 已注册。
3. **TileEntity NPE on load**：`readCustomNBT` 时 `world` 字段可能为 null，不要在其中调用 `world.isRemote`。
4. **配方校验失败**：`IItemHandlerModifiable.getSlots()` 需要足够槽位让 MMCE 通过空间校验；拷贝构造器兼容性也要注意。
5. **中文乱码**：Gradle 和 IDE 都要用 UTF-8，build.gradle 中设置 `options.encoding = 'UTF-8'`。

## 许可

本项目仅作为学习示例。

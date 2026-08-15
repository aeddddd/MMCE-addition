# MMCE Addition

一个面向 Minecraft 1.12.2 的 ModularMachinery Community Edition（MMCE）附属模组，
为 AE2 Unofficial Extended Life（AE2UEL）提供**异步 ME 输出总成**。

## 功能

添加了两个仓室:ME异步物品输出总线和ME异步流体输出仓,并且允许替换任意输出仓室为该仓室.这两个仓室是为了在极多 MMCE 的多方块的整合包内提供较好的性能

Two warehouses have been added: ME asynchronous article output bus and ME asynchronous 
fluid output warehouse, and it is allowed to replace any output warehouse as the warehouse.
These two warehouses are designed to provide better performance in the integration package 
of very many MMCE multi-party blocks

修改了类似MBC的土味并行机制, 现在会自动识别并且修改为真并行.

虚拟并行仓: 你是否已经厌倦了反反复复的堆量? 而并行仓要么不存在, 要么造价高昂. 现在你可以使用虚拟并行仓来避免这个问题. 虚拟并行仓提供了一个额外的并行乘区, 你可以把一个多方块完整的全部材料打包为一个机器数据, 放入虚拟并行仓会为其提供(原本并行)*(1+N)的并行.N 为匹配机器数据数量大小.
## 依赖

- Minecraft 1.12.2
- Forge 14.23.5.2847
- ModularMachinery Community Edition
- AE2 Unofficial Extended Life

## 未来更新计划
新的仓室:
- ME样板总成镜像
- 基础超频仓
- 无损超频仓

暂无,当然你可以向我提出需求,不保证一定会实现


## 下载 Download
目前仅 github ,如果你存在需求, 可以考虑上传cf

Only github now, but If you have needs, i can consider uploading cf

## 其他 Q&A
Q:为什么制作这个模组?

A:通过对于 @shenkongsk 的 MeatballCraft 的邻近毕业存档分析, 我发现后期超过3000+的多方块的频繁输出是性能的问题的根源,
事实上, 即使使用了ME机械输出总线, 也同样存在问题, 于是我制作了这个mod. 当然, `Long` 的缓存上限需求也是他所提出的.

Q:配方问题?

A:自行通过crt添加

Q:在整合包使用?

A:随意

Q:是否存在办法批量替换之前的ME机械输出总线?

A:`/mmceaddition replaceMeItemBus` 需要权限,遍历已加载区块.把所有 MEItemOutputBus 替换为
TileMEAsyncItemOutputBus 替换前读取原仓的 IOInventory 内容,转移进异步缓冲, 注意, 这是性能测试
指令, 使用后果自负.
# Cleanroom 0.4.0 专用服务器兼容补丁

仅当加载器报告版本精确为 `0.4.0` 且物理侧为专用服务器时启用。在 Forge Loader 的
`identifyMods` 中隔离 `KirinoCore.identifyMods(List)` 客户端渲染模块发现调用，保留其它模组发现流程。
客户端和其它 Cleanroom 版本不修改。不会修改安装器、加载器 jar 或版本字符串。

原版 0.4.0 在这个调用处加载 `EntityPlayerSP` 并崩溃，即使 mods 目录为空。之后官方将 Kirino
拆分为客户端/公共模块：
https://github.com/CleanroomMC/Cleanroom/commit/4c6fb61
https://github.com/CleanroomMC/Cleanroom/commit/d7f9532

此补丁需要作为独立 coremod 一同放入 0.4.0 专服的 mods 文件夹。验收报告必须记录实际安装了该补丁；
空服对照失败日志保留在 `.dev/ae2-addons-verification/loader-baseline/server`。

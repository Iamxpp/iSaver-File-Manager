# iSaver Android 兼容矩阵

> 验收日期：2026-08-14
> 目标版本：0.5.x（M8 + M9）

| 环境 | API | Root | 验收范围 | 结果 |
| --- | ---: | --- | --- | --- |
| Android Emulator / Google APIs x86_64 | 29 | 无 | 安装、冷启动、Root 阻断、重新检测、强停重建、Root 门禁/文件组件 Compose UI、退出 | 通过（本轮） |
| 小米 9 透明尊享版 / Android 11 | 30 | 有 | 648 JVM、Lint、四 ABI 构建、23 组 Root/Android 专项、共享存储可见工作流、10,000 项与 512 MiB 稳定性、性能和冷启动 | 通过（本轮） |
| Android Emulator / Google APIs x86_64 | 33 | 无 | 安装、冷启动、Root 阻断、重新检测、强停重建、Root 门禁/文件组件 Compose UI、退出 | 通过（本轮） |
| Android Emulator / Google APIs x86_64 | 35 | 无 | 安装、冷启动、Root 阻断、重新检测、强停重建、Root 门禁/文件组件 Compose UI、退出 | 通过（本轮） |

## 结论

- API 29、33、35 的 Stock Emulator 不提供 `su`，结果只证明应用在对应 Android 版本能正确阻断非 Root 用户并保持 UI/生命周期稳定，不代表 Root 文件功能通过。
- API 30 小米 9 是当前唯一完整 Root 文件功能验收设备。
- 当前可声明：0.5.x 应用可安装并运行于 Android 10、11、13、15；Root 文件功能已在 Android 11 小米 9 上完成 M9 本地门禁。不得声明所有 Android 10 至 15 设备的 Root 文件系统兼容性已经覆盖。
- Emulator 验收脚本：`scripts/verify_android_compatibility.ps1 -Api 29|33|35`。

## 已知限制

- 不同 Root 管理器、SELinux 策略、OEM ROM 和文件系统映射仍可能产生差异。
- API 31、32、34 未单独运行，当前由相邻版本和 compile/target SDK 验证覆盖，不列为实机验收通过；横屏/竖屏双窗已在小米 9 验收，平板和折叠屏仍主要由响应式实现与 Emulator 覆盖。

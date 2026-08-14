# iSaver 命名空间迁移与最近页性能验收

> 日期：2026-08-14
> 版本：0.5.1（versionCode 5）
> 设备：小米 9 Transparent Edition / Android 11 / API 30

## 1. 命名空间

- 主应用 `namespace` 与 `applicationId`：`com.isaver.filemanager`。
- instrumentation runner：`com.isaver.filemanager.ISaverTestRunner`。
- 测试包：`com.isaver.filemanager.test`。
- `:remote` 模块：`com.isaver.filemanager.remote`，仍未接入 `:app`。
- 主、测试、androidTest、debug、remote 源码目录、JNI 符号、Provider authority、ADB 脚本和历史技术文档路径均已同步。
- GitHub URL 中的账号名是实际远端地址，不属于 Android 源码命名空间。

## 2. 性能问题与修复

- 原实现每次最近项目 `stat` 后都会写入 `available`；Room 表失效再次触发 `observeRecent()`，ViewModel 重置为 Checking 并重新 `stat`，形成反馈环。
- DAO 更新增加值变化条件，相同可用性返回 0 且不写数据库。
- Repository 对仅 `available` 变化的发射去重；最近项目内容、顺序、活动或备注变化仍正常发射。
- ViewModel 只在缓存状态变化时持久化，并在重新校验期间复用已解析状态。
- Compose 不再为已有列表显示底部“正在检查最近项目”提示。
- `accessMode` 初次收集不再触发重复刷新，只有 Root/非 Root 模式真实切换才刷新。

## 3. 自动化验证

- 100 个 JVM 套件、668 个测试：0 failure / 0 error。
- `lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:assembleRelease`、`:remote:assembleDebug`：通过。
- unsigned Release APK：3,842,993 bytes。
- 旧包名在 tracked 源码、测试、脚本和物理源码路径中的扫描结果均为 0。

## 4. 小米 9 验证

- 最近页 UI：3/3；启动 smoke：2/2；非 Root 只读：1/1；Root stream：7/7。
- UIAutomator 确认最近页真实条目存在且无“正在检查”文本。
- 最近页稳定后 10 秒 `/proc/<pid>/stat` CPU tick 增量为 0；5 秒 `gfxinfo` 渲染 0 帧，无后台闪烁。
- 设备仅安装 `com.isaver.filemanager` 与 `com.isaver.filemanager.test`；查询迁移前旧包返回 `Unable to find package`。

## 5. 结论

命名空间迁移与最近页反馈环修复通过。本轮未增加定时轮询；最近项目只在数据内容、访问模式或用户刷新事件发生时校验。M10 远程能力继续冻结。

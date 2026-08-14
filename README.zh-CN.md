# iSaver

[English](README.md) | [中文](README.zh-CN.md)

iSaver 是一款面向 Android 真实文件系统的文件管理器。Root 模式提供完整文件管理能力，非 Root 模式可只读浏览应用有权访问的目录。

当前公开版本：`0.1.3`。

## 功能

- Root 权限门禁，支持明确的重试和退出操作。
- 非 Root 只读浏览，可访问应用当前有权读取的目录。
- 最近项目、位置、Root 浏览器三个主入口。
- 内置通用 Android 存储位置，并支持用虚拟文件夹组织真实文件/目录引用。
- 列表/网格视图、搜索、稳定排序和新建文件夹。
- 单文件 `ACTION_SEND` 和 `content://` `ACTION_VIEW` 保存流程。
- 底部内联保存栏，支持长文件名放大编辑，保存失败后可改名重试。
- Root 流式写入，使用身份绑定的临时 stage，并保证不覆盖已有文件。
- ZIP 创建，以及 ZIP、TAR、TAR.GZ、7Z、RAR 的安全浏览和解压。

远程 SFTP、FTPS、FTP 相关代码仍处于实验阶段，本版本不会作为公开支持功能展示。

## 产品文档

- [需求说明书](docs/product/iSaver_PRD_需求说明书.md)
- [系统设计文档](docs/product/iSaver_SDD_系统设计文档.md)
- [现代 Root 文件管理器完整产品与技术规格](docs/product/iSaver_现代Root文件管理器完整产品与技术规格.md)
- [M7.1 虚拟视图位置统一改造与验收](docs/product/iSaver_M7.1_虚拟视图位置统一改造需求与技术落地.md)

## 要求

- Android 10 或更高版本。
- 写操作和受保护路径需要可用的 Root 方案并提供 `su`。
- 建议授予 iSaver Root 权限以使用完整功能。

iSaver 不提供 Root 能力，也不集成 SAF 或 Shizuku。非 Root 模式仅提供只读访问，并受 Android 应用沙箱权限限制。

## 安装

可安装 APK 会通过 GitHub Releases 发布：

- [最新发行版](https://github.com/Iamxpp/iSaver-File-Manager/releases/latest)

在配置私有 release 签名之前，发行资产是 debug 签名的开发构建，文件名格式为 `iSaver-<tag>-debug.apk`。

## 构建

项目需要 JDK 21 和以下 Android SDK 包：

- Android SDK Platform 35
- Android SDK Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1

在 PowerShell 中运行本地检查：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleDebugAndroidTest
```

debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 设备验证

Root 保存/分享行为应在 Root 测试机上验证：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify_device_root_transfer.ps1 -Serial d51f42ac -AutoGrantKernelSU
```

预期结果：

```text
RootStreamTransferInstrumentedTest: OK
PASS: device root transfer verification completed
```

主要 Root 测试设备是运行 Android 11/API 30 的小米 9。小米 17 不在 ADB 环境时，通过用户提供的日志验证问题。API 29、33、35 有非 Root 兼容性覆盖。详细范围见 [兼容性矩阵](docs/testing/android-compatibility-matrix.md)。

Root 和集成测试使用 `/data/local/tmp/isaver-test` 下的专用路径。不要把真实应用数据当作可删除测试夹具。

## 安全

iSaver 只执行边界明确的 Root 操作，不向 UI 或 ViewModel 暴露通用 shell 执行器。文件发布使用私有 incoming 缓存、身份绑定 stage 和不覆盖已有文件的最终发布流程。

报告漏洞前请阅读 [SECURITY.md](SECURITY.md)。不要在公开 issue 中附带私有路径、文件、数据库、ADB 密钥、签名密钥或完整设备日志。

## 贡献

请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。行为变更应先补失败测试，并通过相关 Gradle 和设备检查后再合并。

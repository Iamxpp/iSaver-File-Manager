# iSaver 项目完成度审计（2026-07-18）

## 1. 审计结论

项目文档中的所有内容**尚未全部实现**。

当前代码已经形成可靠的 Root 本地文件浏览与单文件分享另存核心，M1 的 Root 最小闭环、M3 的安全保存链路和共享存储发布修复具备自动化与小米 9 证据。位置聚合、微信模板、自定义位置、列表/图标排序、新建文件夹、ZIP 创建以及归档/远程协议后端也已经存在。

尚未完成的主要产品闭环是：

1. “最近项目”页面仍是固定空态，访问、归档和远程活动没有完整接入最近记录。
2. 归档浏览与解压只有 Repository/Engine 和测试，没有可操作的产品 UI；目录也不能在当前浏览 UI 中作为压缩源被选择。
3. 远程 UI 仅支持连接、显示单层列表、刷新和新建文件夹；缺少远程目录导航、上传、下载到 Root 目标、重命名、删除确认、进度/取消与断线重连。
4. 远程连接元数据没有 Room 持久化；当前只保存 Keystore 加密后的秘密值。
5. 自定义位置不支持只读位置的添加，缺少系统关键路径风险策略和单项手动重新校验。
6. M5 的 Android 版本矩阵、开源 README/LICENSE/CONTRIBUTING/SECURITY 和 CI 工作流尚未完成。

## 2. 判定规则

| 状态 | 判定 |
| --- | --- |
| 已实现 | 生产代码形成用户可用闭环，并有对应自动化或真机证据 |
| 部分实现 | 后端、UI 或异常处理只完成其中一部分，用户无法完成文档描述的全部流程 |
| 未实现 | 没有对应生产入口或核心实现 |
| 待外部验收 | 本地实现存在，但需要未提供的系统版本、服务器或外部环境完成验收 |

审计同时检查生产代码、单元/Compose/instrumentation 测试、Manifest、Gradle 依赖、Room schema、native helper、Git 里程碑和小米 9 ADB 证据。仅有接口、数据类或测试替身不计为产品功能完成。

## 3. PRD 功能需求追踪

| PRD | 状态 | 已有实现与证据 | 缺口 |
| --- | --- | --- | --- |
| 5.1 Root 启动门禁 | 已实现 | `RootGateViewModel`、`RootGateScreen`、`LibsuRootSession`；JVM/Compose 测试；小米 9 `su -c id` 返回 UID 0 | Root 被真机临时拒绝后的恢复流程本轮未重新操作 Root 管理器 |
| 5.2 三标签主页与视图 | 部分实现 | `ISaverHomeViewModel`、`ISaverHomeScreen`、`LocationHomeScreen`；Views/Browse 和统一头部、底栏、菜单已接入 | `RecentEmptyScreen` 始终显示“暂无最近项目”；Views 不展示 `recentLocations`；未形成文档要求的最近访问/保存/归档/远程聚合 |
| 5.3 微信路径模板 | 已实现 | `LocationCatalog` 独立定义 5 个候选；`LocationResolver` Root 探测、并发限制和 canonical 去重；对应 JVM 测试 | 仅在小米 9 验收；其他 ROM、微信分身和新版本路径仍需扩展 |
| 5.4 Root 目录浏览 | 部分实现 | native `list-dir`、`NativeDirectoryListingParser`、`DirectorySnapshotCache`、120ms 延迟加载提示、200 项呈现窗口、搜索、列表/网格、四种排序与 DataStore 持久化；性能 instrumentation 测试 | 点击普通文件当前只切换压缩选择，不展示基础信息；不可读普通文件没有与不可读目录一致的禁用处理；未形成文件详情页 |
| 5.5 自定义 Root 位置 | 部分实现 | Room DAO/Repository、添加/编辑/移除、重复路径拒绝、失效配置保留、异步重新探测和 UI 对话框 | `LocationHomeViewModel.mutate` 强制要求 writable，导致只读位置不能按 PRD 添加；没有 `/system`、`/vendor`、`/product`、`/boot` 风险策略；没有单项“重新校验”动作 |
| 5.6 新建文件夹 | 已实现 | `FolderName`、typed `RootFileSystem.createDirectory`、默认名称全选、错误映射、成功刷新与定位；JVM/Compose 测试 | 仍只在当前小米 9/API 30 验收 |
| 5.7 Intent 分享接收 | 已实现 | Manifest 仅注册单文件 SEND 和 content VIEW；`ShareIntentParser` 覆盖 EXTRA_STREAM、单项 ClipData、冲突、多项拒绝、2 秒超时；`singleTop/onNewIntent`；三标签内嵌保存 UI 与 instrumentation 测试 | 真实第三方 Provider 行为仍依赖各来源应用；当前重点证据来自固定 debug Provider/ADB 流程 |
| 5.8 Root 文件保存 | 已实现 | 私有 UUID cache、一次性 Root/Shell token Provider、native stage、精确流长度、POSIX `RENAME_NOREPLACE`、FUSE `O_EXCL` 兼容、重名、队列、取消边界、Uncertain 和 24 小时 orphan 清理；共享存储/碰撞真机测试 | 尚无 Android 10、12+ 和其他 Root 管理器矩阵；没有媒体扫描调用，需在媒体类目标场景补验是否必要 |
| 5.9 压缩包 | 部分实现 | `LocalArchiveEngine` 支持 ZIP/TAR/TAR.GZ/7Z/RAR 检查与解压，`ArchivePathPolicy`/`ArchiveLimits` 防穿越和炸弹，`ArchiveRepository` 通过 Root 缓存桥发布；ZIP 创建和 inspect 在真机测试中到达成功 | 产品 UI 只接入选择普通文件并创建 ZIP；没有归档浏览/解压入口、目标选择、可见进度或取消按钮；目录点击进入目录而不是选为压缩源；2026-07-18 新鲜运行 `ArchiveRootInstrumentedTest` 时解压到尚不存在的目标目录失败，Root 闭环当前不是绿色 |
| 5.10 SFTP/FTPS/FTP | 部分实现 | typed 协议适配器、SFTP 主机密钥固定、FTPS CA+证书指纹、FTP 明文确认、Keystore AES-GCM 秘密存储、连接对话框、单层列表/刷新/新建目录、传输接口和下载临时仓库测试 | 无远程目录进入/返回；上传/下载未接 UI，下载也未发布到用户选择的 Root 目录；无 rename/delete API 与二次确认；无断线重连；无可见进度/取消；连接元数据未写 Room；无真实服务器验收 |

## 4. 非功能与安全要求

| 项目 | 状态 | 证据与缺口 |
| --- | --- | --- |
| Root 操作分层与命令安全 | 已实现 | UI/ViewModel 不接受任意命令；`RootFileSystem`、`RootSession`、native helper 为窄接口；参数引用和恶意文件名测试覆盖完整 |
| 目录性能 | 已实现（小米 9） | 单进程 native 枚举、16 项/2 秒 LRU、缓存先显示、120ms 加载阈值；专用夹具新鲜结果：helper 200 项 P95 34ms、1000 项 P95 51ms，App 冷 200 项 P95 40.77ms、缓存 P95 1.45ms、1000 项首屏 P95 386.25ms；其他设备待测 |
| 保存安全 | 已实现（当前设备） | 私有 cache、一次性 token、Root-owned stage、身份复核、无覆盖发布和精确清理；FUSE 回归 2 项已加入 Root instrumentation |
| 归档安全 | 部分实现 | 路径策略、资源限制和取消清理有测试；用户 UI 未暴露 inspect/extract，因此产品闭环未完成 |
| 远程安全 | 部分实现 | 指纹/证书/FTP 风险门禁与 Keystore 存储存在；连接 profile 没有 Room 生命周期，真实 TLS/SSH 服务器尚未验收 |
| 进程恢复 | 部分实现 | Home 和分享保存有 SavedState/RequiresReshare 设计；最近页、归档任务和远程连接没有完整恢复模型 |
| 版本兼容 | 待外部验收 | Gradle `minSdk=29`、`targetSdk=35`，但真机仅 Android 11/API 30 |
| 开源准备 | 未实现 | 仓库缺少 README、主 LICENSE、CONTRIBUTING、SECURITY 与 CI workflow；只有 `ARCHIVE-LICENSES.md` 和 `REMOTE-SERVER-SECURITY.md` |

## 5. 里程碑完成度

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M0 项目与规范 | 部分实现 | Android/Gradle/Git/Skill/PRD/SDD 已落地；文档要求的基础 CI 结构不存在 |
| M1 Root 最小闭环 | 已实现 | Root 门禁、安全路径、typed Root 层、目录列表和小米 9 闭环存在 |
| M2 位置与目录浏览 | 部分实现 | 通用位置、微信、自定义位置、浏览、新建目录已接入；只读自定义位置、关键路径风险、手动重新校验仍缺失 |
| M3 分享另存与本地视图 | 部分实现 | 分享保存、重名、错误恢复、列表/网格/排序已完成；最近项目 UI 与完整记录来源未完成 |
| M4 压缩归档 | 部分实现 | 后端和测试骨架已完成，但当前 ZIP Root instrumentation 在解压阶段失败；归档浏览/解压/取消的产品 UI 也未完成 |
| M5 发布准备 | 部分实现 | 目录性能、主要 UI、Force Dark 修复和新启动图标已推进；版本矩阵、开源材料、许可证总览和 CI 未完成 |
| M6 远程服务器 | 部分实现 | 安全协议适配器及连接根目录雏形存在；完整远程文件管理和真实服务器验收未完成 |

## 6. 首版验收标准核对

### 已具备当前设备证据

- Root 授权进入主页、Root 阻断 UI 契约。
- Views/Browse、微信与通用位置、自定义路径基础管理。
- Root 浏览、新建文件夹、列表/网格和排序偏好。
- ACTION_SEND/ACTION_VIEW 三标签内嵌保存、双字段名称编辑、共享存储发布与同名保护。
- native 快速目录枚举与缓存。
- ZIP 后端 create/inspect；extract Root 闭环当前有确定失败，不能计为通过。

### 尚不能验收通过

- 最近项目必须展示真实记录，而不是固定空页面。
- 用户必须能从 UI 浏览和解压 ZIP/TAR/TAR.GZ/7Z/RAR，并能取消和观察进度。
- 用户必须能在远程目录中导航、上传、下载、重命名和删除，并看到进度/取消/断线恢复。
- SFTP、FTPS、FTP 必须在真实服务器上分别完成证书/主机密钥、上传、下载和失败清理验收。
- Android 10 至最新可用版本兼容矩阵未建立。
- 开源发布文件与 CI 未完成。

## 7. 建议后续顺序

1. 完成 Recent ViewModel/UI，并把成功访问、保存、压缩、解压和远程传输统一记录。
2. 将归档 inspect/extract 接入文件点击与目标目录选择，补进度和取消 UI，再运行多格式真机恶意包验收。
3. 完成远程目录导航与上传/下载到 Root 的端到端 UI，然后增加 rename/delete 二次确认、断线重连和 Room profile。
4. 修复只读自定义位置、关键系统路径风险提示和单项重新校验。
5. 建立 Android 10/11/12+ 兼容测试，补 README、LICENSE、CONTRIBUTING、SECURITY 和 CI 后再标记首版完成。

## 8. 本轮图标变更

- Manifest 已显式声明普通与圆形启动图标。
- adaptive icon 使用冷白浅蓝背景和与 `FolderGlyph` 一致的双层蓝色文件夹。
- `LauncherIconInstrumentedTest` 已完成 RED（图标资源 ID 为 0）到 GREEN（1/1）。
- 小米 9 MIUI 桌面截图显示文件夹主体居中、未被圆角方形遮罩裁切；截图位于 ignored `captures/`，不提交仓库。

## 9. 新鲜验证结果

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：成功。
- `LauncherIconInstrumentedTest`：`OK (1 test)`。
- Provider、启动图标、数据库迁移、Intent 解析、Root 保存、性能和归档组合：13 项中 11 项通过；性能项首次因未准备专用夹具返回 `NOT_FOUND`，归档解压失败。
- 使用 `scripts/benchmark_root_listing.ps1` 准备并最终清理专用夹具后，性能 instrumentation `OK (1 test)`，全部预算通过。
- `ArchiveRootInstrumentedTest` 单独复跑仍为 1/1 失败：`ZIP extract failed: Failure(code=COMMAND_FAILED, message=保存失败，请稍后重试)`；证明不是性能夹具或测试组合干扰。
- `MainActivitySmokeTest` 单独运行 60 秒未返回；本轮没有把它计为通过，也未为图标任务修改该既有测试/Activity 流程。

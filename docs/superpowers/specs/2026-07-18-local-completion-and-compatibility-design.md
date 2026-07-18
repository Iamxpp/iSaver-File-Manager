# iSaver 本地功能补全与 Android 兼容矩阵设计

> 日期：2026-07-18
> 状态：设计已确认
> 目标版本：iSaver 0.1.0 本地功能预发布版

## 1. 范围与优先级

本轮只完成本地文件管理闭环和 Android 兼容矩阵：

1. 最近项目真实页面及访问、保存、压缩、解压记录。
2. ZIP、TAR、TAR.GZ、7Z、RAR 分层浏览、安全解压、进度和取消。
3. 只读自定义位置、系统关键路径保护提示和单项重新校验。
4. 普通文件基础信息和不可读条目状态。
5. API 29、30、33、35 Android 兼容矩阵。

远程目录导航、上传下载、重命名、删除、断线恢复、Room profile 和 SFTP/FTPS/FTP 真实服务器验收整体下放 P3，不阻断 0.1.0 本地版发布。

首版继续保持 Root-only，不增加 SAF、Shizuku、非 Root 降级或本地任意删除、移动、重命名和编辑。

## 2. 总体架构

采用独立状态模块，不把最近项目、归档和文件信息继续塞入 `BrowserViewModel`：

```text
MainActivity
├─ ISaverHomeViewModel       三标签与目录导航
├─ BrowserViewModel          真实 Root 目录浏览与压缩选择
├─ RecentViewModel           最近记录观察、重新校验与点击分流
├─ ArchiveViewModel          归档检查、树形导航、目标选择与解压状态
├─ LocationHomeViewModel     自定义位置校验与重新校验
└─ TransferViewModel         分享另存，保持现有边界
```

`RecentRepository` 仍是最近活动唯一持久化入口；`ArchiveRepository` 仍是归档数据入口；Root 写操作继续通过 `RootFileSystem` typed API 和固定 native helper 完成。Composable 不调用 Root、不拼接命令，也不直接访问 Room。

## 3. 最近项目

### 3.1 数据模型

`RecentActivity` 扩展为：

- `ACCESSED`：成功进入 canonical 目录，或成功打开普通文件信息/归档。
- `SAVED`：分享文件确定成功发布。
- `COMPRESSED`：ZIP 确定成功发布。
- `EXTRACTED`：归档目录确定成功整体发布。

`RecentItemType` 保持 `DIRECTORY`、`FILE`、`ARCHIVE`。新增枚举值以字符串写入现有 Room 字段，不改变表结构或 schema 版本。旧 `ACCESSED`、`SAVED` 数据继续可读。

只有确定成功结果写入最近项目。失败、取消、`OUTCOME_UNCERTAIN`、检查失败和仅开始加载均不得记录。

### 3.2 记录时机

- `BrowserViewModel` 成功读取目录后异步 canonicalize，并通过注入回调记录目录访问。
- 普通文件信息成功展示后记录文件访问。
- 归档 `inspect` 成功后记录归档访问。
- 分享保存沿用现有 `TransferViewModel` 成功记录。
- ZIP 发布成功后记录发布结果中的归档 `DirectoryEntry`。
- 解压 staging 目录整体发布成功后记录最终目录。

最近记录失败不反转已经完成的文件操作；UI 显示非阻断警告“操作已完成，但无法更新最近项目”。

### 3.3 页面与点击行为

`RecentViewModel` 观察 `RecentRepository.observeRecent()`，并以最多四个并发 `stat` 重新校验当前条目。数据库为空时才显示“暂无最近项目”。

- 可用目录：点击重新进入目录，来源标签保持“最近项目”。
- 可用普通文件：点击打开文件信息。
- 可用归档：点击进入归档浏览。
- 已失效或类型变化：保留行，显示“项目不可用”，禁止打开。
- 页面提供刷新动作；单个条目重新校验不会阻塞其他条目。

最近页面使用现有列表/三列图标显示偏好和四种排序，不建立第二套视觉组件。

## 4. 文件点击、信息与压缩选择

### 4.1 普通点击

不在选择模式时：

- 可读目录单击进入。
- 可读且扩展名受支持的归档单击进入归档浏览。
- 其他文件单击打开文件信息。
- 不可读文件仍可打开信息页，只展示已有 `stat/list-dir` 元数据，不读取内容。
- 不可读目录和符号链接目录不进入，列表元数据显示“不可读”或“符号链接”。

### 4.2 文件信息

文件信息使用轻量 `FileInfoDialog`，展示：

- 名称。
- 完整绝对路径。
- 类型：文件、目录、其他、归档格式或符号链接。
- 大小；未知时显示“—”。
- 修改时间；未知时显示“—”。
- 可读、可写状态。

信息页不提供编辑、删除、移动、重命名或任意格式预览。

### 4.3 压缩选择模式

- 文件或目录长按进入选择模式并选中该条目。
- 选择模式中单击文件或目录切换选中。
- 不可读、符号链接和 `OTHER` 条目不能选为压缩源。
- 选择模式显示已选数量、退出/清空动作；三点菜单中的“压缩文件”在至少选中一项时启用。
- 普通单击不再与压缩选择冲突。

ZIP 创建成功后退出选择模式、刷新当前目录并记录最终归档；失败保留选择，便于修改名称后显式重试；取消退出运行态但不伪造成功。

## 5. 归档浏览

### 5.1 支持格式

通过文件名后缀初筛，最终以 `LocalArchiveEngine.inspect` 结果为准：

- `.zip`
- `.tar`
- `.tar.gz` 和 `.tgz`
- `.7z`
- `.rar`

文件不可读、检查失败、格式不支持或超过安全限制时显示结构化错误，不进入空白归档页面。

### 5.2 分层模型

`ArchiveListing.entries` 保持扁平安全相对路径；新增纯 Kotlin `ArchiveTree` 将路径转换为当前前缀下的直接子项：

- 合成缺失的中间目录节点。
- 目录优先、名称自然排序。
- 点击目录进入下一级，返回逐级退出。
- 文件行展示路径末段、大小和压缩大小。
- 归档内容只浏览元数据，不实现单文件预览或编辑。

`ArchiveScreen` 使用与真实目录一致的紧凑标题、搜索、列表/网格和底部三标签视觉，但归档内部导航不改变选中的来源标签。

## 6. 安全解压与取消

### 6.1 用户流程

1. 用户在归档页面点击“解压”。
2. 返回三标签位置视图选择一个已存在、可写、非系统保护区域的真实目录。
3. 顶栏动作显示“解压到此处”；逻辑页面或只读目录禁用。
4. 确认后显示归档名称、当前阶段、当前条目和条目/字节进度。
5. 成功后进入最终解压目录并记录 `EXTRACTED`。

默认最终目录名取归档文件名去除已识别复合扩展，例如 `backup.tar.gz` 得到 `backup`。碰撞时使用 `backup (1)`，不覆盖现有目录。

### 6.2 隐藏 staging

为保证失败或取消后没有半成品，禁止继续沿用“逐文件直接发布到用户目标”的现有方式。解压改为：

```text
用户目标目录
  └─ .isaver-extract-<uuid>   Root-owned 隐藏 staging
       └─ 解压内容

全部完成与复核后
  renameat2(RENAME_NOREPLACE)
  .isaver-extract-<uuid> -> backup
```

新增固定、typed Root 原语：

- `prepareExtractionStage(parent)`：创建隐藏目录并返回 parent/stage device+inode 身份。
- `createExtractionDirectory(stage, relativeComponents)`：只在已绑定 stage FD 内创建安全目录。
- `transferIntoExtractionStage(stage, relativeParent, source, finalName)`：继续复用一次性私有缓存流，但 helper 从已绑定 stage FD 逐组件打开目录，拒绝符号链接和身份变化，不把普通 Root 路径当作目标。
- `commitExtractionStage(stage, finalName)`：复核 parent/stage 身份、无符号链接后执行目录级无覆盖原子 rename。
- `cleanupExtractionStage(stage)`：只递归删除身份匹配的隐藏 stage；遍历使用 `openat/fstatat/unlinkat`、不跟随符号链接，不接受任意用户路径删除。

native helper 仅新增上述固定子命令和版本化参数，不增加通用 `rm`、任意 rename 或 UI 可调用的 shell 接口。

### 6.3 取消语义

- 读取 Root 归档、私有缓存检查、本地解压和 staging 写入阶段可取消。
- 收到取消后停止创建新条目，进入“正在清理”，以 `NonCancellable` 完成身份绑定 staging 清理。
- 目录级最终 rename 是短暂不可取消窗口；UI 显示“正在完成”，禁用取消，等待并核对唯一结果。
- 确定失败或取消清理 staging；不确定 rename 结果不得盲删，显示“结果不确定，请刷新目标目录核对”。
- 已存在用户文件和目录永不因本次取消被删除。

## 7. 自定义只读位置与系统保护区域

### 7.1 添加和编辑

添加或编辑只要求路径存在、是目录且可读；不可写不再拒绝保存。实时可写状态继续通过 Root `stat` 得出，不持久化为永久事实。

每个自定义位置提供“重新校验”，只重新探测该项并显示 `Checking`，不重启整个位置页任务。

### 7.2 系统保护策略

新增纯 Kotlin `RootPathRiskPolicy`。以下路径本身及其后代为 `PROTECTED_SYSTEM`：

- `/system`
- `/vendor`
- `/product`
- `/boot`

判断按路径组件边界进行，`/system2` 不属于 `/system`。

保护区域统一行为：

- 允许只读浏览。
- 位置行显示“系统保护区域 · 只读”。
- 打开目录后显示醒目但不遮挡内容的保护提示。
- 即使 Root `stat` 返回 writable，也禁止分享保存、新建文件夹和解压目标选择。
- 该策略同时作用于自定义位置、最近项目和从 `/` 浏览进入的路径，不能只在添加对话框提示。

## 8. 错误与恢复

- 最近项重新校验失败：保留记录并标记不可用。
- 普通文件元数据缺失：未知字段显示“—”，不崩溃。
- 归档检查失败：留在来源目录并显示错误。
- 解压目标只读、变成符号链接、canonical identity 变化：开始写入前阻断。
- 解压本地阶段失败：清理私有缓存和本地临时目录。
- staging 写入失败/取消：清理身份绑定 staging；清理失败升级为结果不确定并要求人工核对。
- 最近记录失败：主操作保持成功，附非阻断警告。
- Root 中途失效：不自动重放写操作，返回 Root 门禁或显式重试状态。

## 9. Android 兼容矩阵

### 9.1 测试环境

| API | Android | 环境 | Root 验收范围 |
| --- | --- | --- | --- |
| 29 | 10 | 干净 Android Emulator | 无 `su`；安装、启动、Root 阻断、重试、退出、进程重建 |
| 30 | 11 | 小米 9 透明尊享版 `d51f42ac` | 完整 Root 发布门禁和本轮本地功能 |
| 33 | 13 | 干净 Android Emulator | 无 `su`；安装、启动、Root 阻断、主题、进程重建 |
| 35 | 15 | 干净 Android Emulator | 无 `su`；最新 targetSdk 安装、启动、Root 阻断、主题、进程重建 |

本机 Android SDK 当前未安装 Emulator 和系统镜像。本轮允许通过 `sdkmanager` 安装 `emulator`、对应 `platforms` 与 x86_64 system images，并创建名称带 `isaver-test-` 前缀的临时 AVD。不得覆盖用户已有 AVD。

### 9.2 判定规则

- Stock Emulator 没有 `su` 时，只能计为非 Root 门禁兼容，不能计为 Root 文件功能通过。
- API 30 小米 9 必须执行 `scripts/verify_release_gates.ps1 -Serial d51f42ac`，并增加本轮功能 instrumentation。
- 每个矩阵项记录镜像、ABI、API、启动结果、测试范围、失败项和限制。
- 下载失败、虚拟化不可用或镜像无法启动按外部环境阻断记录，不得伪造通过。

矩阵结果写入 `docs/testing/android-compatibility-matrix.md`，不提交 AVD、system image、APK、截图中的用户数据或本机 SDK 路径。

## 10. TDD 与验收

每个切片严格执行 RED、GREEN、REFACTOR：

1. 最近枚举、记录时机、失效保留和点击分流 JVM/Compose 测试。
2. 文件点击与长按选择、目录压缩来源、信息字段和不可读状态测试。
3. `ArchiveTree` 目录合成/导航测试；五种格式 inspect 测试。
4. extraction stage 身份、路径边界、无覆盖 commit、递归安全清理和取消测试。
5. 只读位置保存、保护路径组件边界、单项重新校验和写入阻断测试。
6. 小米 9 专用目录创建 ZIP、五种归档浏览/解压、取消清理和最近记录 instrumentation。
7. API 29/33/35 Emulator Root 阻断与进程重建冒烟。
8. 完整 JVM、Lint、Debug build 和发布门禁。

真机测试只使用 `/data/local/tmp/isaver-test` 等专用夹具，不读取、修改或上传真实微信文件。

## 11. 完成标准

- “最近项目”不再是固定空页面，四类确定成功活动可见且可重新打开。
- 单击普通文件显示完整基础信息；不可读状态清晰，不触发内容读取。
- 文件与目录可通过长按进入压缩选择，ZIP 成功记录最近项目。
- 五种归档可分层浏览；解压目标选择、进度、取消、无覆盖发布和 staging 清理形成 UI 闭环。
- 只读自定义位置可保存和浏览；保护区域在所有入口一致禁写；单项重新校验可用。
- API 29/30/33/35 矩阵有真实运行证据和明确限制。
- 完整发布门禁退出码为 0，工作树不含敏感信息、构建产物或测试数据。

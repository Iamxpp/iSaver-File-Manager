# iSaver iOS 风格分享保存选择器设计

**日期：** 2026-07-13
**状态：** 已获用户批准（视觉方案 A，双编辑框文件名）
**入口：** 微信等应用的单文件 ACTION_SEND 与 ACTION_VIEW

## 1. 产品行为

- 普通 Launcher 启动保持三标签主页。
- 微信“使用其他应用打开”PDF 或系统分享给 iSaver 时，直接进入独立全屏保存选择器，不先显示普通主页。
- 保存模式隐藏“最近项目 / 视图 / 浏览”底栏。
- 成功保存后结束本次 Activity，返回来源应用。
- Root 未授权时先显示 Root 阻断；授权成功后继续保留的保存请求。

## 2. Intent 入口

Manifest：

- 保留单文件 ACTION_SEND / */*。
- 新增 ACTION_VIEW，仅 content:// + */* + DEFAULT。
- 不注册 SEND_MULTIPLE、file://、http(s):// 或 BROWSABLE。
- MainActivity 使用 singleTop，冷启动消费一次 intent，暖启动在 onNewIntent 中消费。
- FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY 不重放历史分享。

解析：

- SEND 从单个 EXTRA_STREAM 读取，可接受单项 ClipData；两者不一致时拒绝。
- VIEW 只从 intent.data 读取。
- 仅接受 content://。
- Provider 的 MIME、DISPLAY_NAME、SIZE 查询在 IO dispatcher 中执行，可取消且有界。
- Uri 不传给 Root、不解析真实路径、不写 SavedState/Room/日志。

## 3. 来源缓存

- 元数据解析成功后立即显示选择器，同时后台复制到应用私有 UUID cache。
- 用户可在缓存期间浏览目录；底部显示已缓存字节。
- “存储”按钮必须等待缓存完成。
- 新请求到达时：旧请求尚未触发 Root publish 时取消并清理旧 cache；旧请求已触发 Root publish 时完成 reconciliation，不确定结果优先显示。
- 旋转时 ViewModel 内存能力继续有效；进程死亡只恢复脱敏摘要并要求重新分享。

## 4. 保存选择器布局

### 4.1 顶部

- 使用统一 56dp 紧凑 TopBar。
- 根位置左侧为“取消”；真实目录左侧为返回父级。
- 当前位置名称始终相对屏幕几何居中。
- 右侧为 overflow 与蓝色“存储”。
- 搜索栏紧贴 TopBar。

### 4.2 内容

- 复用 Views/Browse 的列表与三列网格。
- 目录可以进入；文件可见但弱化，不可作为导航目标。
- 只有当前真实目录经 Root 重新验证为 directory、writable、非 symlink 时才可保存。

### 4.3 底部文件名

- 显示当前目录项目数与来源文件类型缩略图。
- 使用两个独立编辑框：stemDraft 为必填主文件名；extensionDraft 为可空扩展名，允许 tar.gz 等多段扩展，不包含界面单独显示的分隔点。
- 来源名按最后一个非首尾点拆分；.env 和 name. 保留在主文件名，扩展名为空。
- 扩展名为空时最终名等于 stemDraft，否则为 stemDraft + 点 + extensionDraft。
- 扩展名拒绝开头点、斜杠和 NUL；组合后通过严格 EntryName 校验：非空、不是点或双点、合法 Unicode、UTF-8 不超过 255 字节。
- Repository 继续用 RENAME_NOREPLACE；同名只在明确 ALREADY_EXISTS 时生成 (1)。

## 5. 状态与按钮

保存按钮仅在以下条件同时成立时启用：

- 私有 cache 已完成并通过 size/identity 校验。
- 当前目标是真实可写非 symlink 目录。
- 两个字段组合后的最终名称合法。
- 当前没有保存、取消或 reconciliation 操作。

UI 状态：Parsing、Caching、Choosing、ValidatingTarget、Saving、Success、Failure、Uncertain、Cancelling、RequiresReshare。

确定失败可重试；OUTCOME_UNCERTAIN 不自动重试、不自动换名、不删除来源 cache，提示刷新目录确认。成功才写最近项目。

## 6. Activity 与导航

- TransferViewModel 由 Application 中的 parser/cache/repository/recent 依赖构建。
- Activity 在 Root Granted 后根据 transfer state 选择普通 ISaverHomeScreen 或 ShareSavePickerScreen。
- 保存模式沿用 LocationHome/Browser 导航数据，但使用独立 scaffold，避免在普通主页堆叠条件分支。
- onNewIntent 交由 ViewModel generation 管理；旧异步结果不得覆盖新请求。

## 7. 测试与真机验收

- Parser JVM：API 29/33/35 的 SEND/VIEW、ClipData、坏 Parcelable、冲突 Uri、file/http、Provider 异常。
- Manifest instrumentation：PDF content VIEW 与 SEND 均能 resolve iSaver，file/http/SEND_MULTIPLE 不能。
- ViewModel：立即缓存、目录能力、双编辑框组合、取消/重试、Root 失效、不确定结果、最近项目和 generation。
- Compose：全屏 picker、隐藏三标签、TopBar 对齐、文件弱化、双输入框、保存能力、进度/失败/不确定状态，semantics 不包含 Uri/cache。
- 小米 9：测试 FileProvider 分别发送 SEND 与 VIEW；验证冷启动、暖启动、旋转、进程重建、中文/空格/引号/重名/大文件/取消/空间不足。
- 最后人工从微信打开一份用户选择的 PDF，只保存到专用测试目录，不修改微信源文件。

## 8. 非目标

- 不预览或编辑 PDF 内容。
- 不实现多文件分享、标签、云盘或非 Root fallback。
- 不伪造 iPhone 状态栏或 Home 指示条。

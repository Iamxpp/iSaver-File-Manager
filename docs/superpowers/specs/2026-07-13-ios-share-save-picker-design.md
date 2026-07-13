# iSaver iOS 风格分享保存选择器设计

**日期：** 2026-07-13
**状态：** 已获用户批准（视觉方案 A，双编辑框文件名）
**入口：** 微信等应用的单文件 ACTION_SEND 与 ACTION_VIEW

## 1. 产品行为

- 普通 Launcher 启动保持三标签主页。
- 微信“使用其他应用打开”PDF 或系统分享给 iSaver 时，直接进入独立全屏保存选择器，不先显示普通主页。
- 保存模式隐藏“最近项目 / 视图 / 浏览”底栏。
- 成功保存且没有 queued generation 时结束本次 Activity 并返回来源应用；有 queue 时记录旧 Success、清理旧 cache 后直接切换到 queued request。
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
- Provider 的 MIME、DISPLAY_NAME、SIZE 查询在 IO dispatcher 中执行，query 使用 `CancellationSignal`，整个解析由 Parser 的异步入口施加 2 秒调用方超时。调用方取消原样传播 `CancellationException`；超时返回独立 `PROVIDER_TIMEOUT`，调用 `CancellationSignal.cancel()` 并由 generation 忽略迟到结果。第三方 Provider 或无 CancellationSignal 的 `getType()` 可能不合作，因此只保证调用方及时结束，不承诺终止对方进程中的工作。
- Uri 不传给 Root、不解析真实路径、不写 SavedState/Room/日志。

## 3. 来源缓存

- 分享 Intent 到达后立即解析并后台复制到应用私有 UUID cache；这一步不依赖 Root，可与 Root 门禁并行，但 Root 未授权前不得开始目录导航或写入。用户从 Root 阻断页退出时清理 cache。
- 用户可在缓存期间浏览目录；底部显示已缓存字节。
- “存储”按钮必须等待缓存完成。
- 每一次 `RootFileSystem.transferFromAppCache` 调用形成一个不可取消的 in-flight publish 窗口。窗口外取消或新请求替换旧请求时，取消旧任务并在 `NonCancellable` 清理其 cache 与已知 stage；窗口内不得取消底层调用、丢弃结果、自动重放或删除 cache，界面进入 Cancelling/Reconciliation 并等待该调用返回。
- in-flight 调用明确返回 `ALREADY_EXISTS` 时，只有在没有取消请求和新 Intent 排队时才可生成 `(n)` 并开始下一次调用；否则在下一 publish 窗口前停止并安全清理。其他 Failure 和 Uncertain 不得自动重试或换名。
- 最多保留一个 queued generation。in-flight 期间到达的新 Intent 立即解析并私有缓存但不抢占界面；更新的 Intent 会替换并清理尚未 publish 的旧 queued cache。旧请求 Success 或不可重试的确定 Failure 会记录终态、清理后激活 queue；可重试的确定 Failure 必须继续显示“重试旧请求”与“清理旧缓存并继续排队请求”，由用户二选一，不能静默切走。
- Uncertain 必须优先展示并在当前进程存活期间保留旧 cache，用户点击“已核对并清理缓存后继续”后才清理并激活 queued request 或关闭。若进程死亡，能力不可恢复，RequiresReshare 摘要必须提示上次结果仍需人工检查；该文件转为 orphan，并适用 24 小时 TTL。
- 进程启动时清理 `incoming` 目录内超过 24 小时且没有当前内存 owner 的孤儿 cache；进程死亡不从 cache 路径恢复能力，较新的孤儿也会在达到 24 小时后清理。
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
- 来源名按最后一个非首尾点拆分；默认 `archive.tar.gz` 拆为 `archive.tar` / `gz`，`.env` 和 `name.` 保留在主文件名且扩展名为空。用户可再改成 `archive` / `tar.gz`。
- 扩展名为空时最终名等于 stemDraft，否则为 stemDraft + 点 + extensionDraft。
- 扩展名拒绝开头点、斜杠和 NUL；组合后通过严格 EntryName 校验：非空、不是点或双点、合法 Unicode、UTF-8 不超过 255 字节。
- `OutputNameDraft` 的字段边界一直传到重名解析器，不得只传合成字符串后再次猜扩展名。Repository 继续用 RENAME_NOREPLACE；仅在明确 ALREADY_EXISTS 时按 `stemDraft + " (n)" + (extensionDraft 为空 ? "" : "." + extensionDraft)` 生成候选，例如 `archive` / `tar.gz` 生成 `archive (1).tar.gz`。

## 5. 状态与按钮

保存按钮仅在以下条件同时成立时启用：

- 私有 cache 已完成并通过 size/identity 校验。
- 当前目标是真实可写非 symlink 目录。
- 两个字段组合后的最终名称合法。
- 当前没有保存、取消或 reconciliation 操作。

UI 状态：Parsing、Caching、Choosing、ValidatingTarget、Saving、Cancelling、Reconciliation、Success、Failure、Uncertain、RequiresReshare。

点击“存储”后必须重新校验 cache 的 regular-file/device/inode/size 身份，以及目标目录的 directory/writable/non-symlink/canonical identity；列表缓存和先前的 writable 只可作为 UI hint。任一身份变化、Root 中断或校验失败都不得进入 publish。

Picker 仍活跃时，可重试的确定失败保留已验证来源 cache；重试必须由用户触发，并重新校验名称、cache 与目标，不得自动重放。取消、退出或不可重试的确定失败清理 cache；成功清理 cache 并写最近项目。OUTCOME_UNCERTAIN 不自动重试、不自动换名，在当前进程内直到显式确认前不删除来源 cache；进程死亡后按 24 小时 orphan TTL 处理。

失败映射固定如下：解析缺少/非法 Uri 与 `SOURCE_UNREADABLE` 进入 RequiresReshare；`PROVIDER_TIMEOUT` 在 Uri capability 仍在内存时可显式重试。缓存来源不可读或 identity/size mismatch 清理 partial cache 并 RequiresReshare；应用 cache 空间/写入失败可显式重新缓存。目标非目录、不可写、symlink 或 canonical identity 变化保留 cache 并返回 Choosing；Root 丢失保留 cache、回到 Root gate，重新授权后仍需用户显式保存。publish 的确定 NO_SPACE、NOT_WRITABLE、COMMAND_FAILED 保留 cache 供显式重试，Root 层必须已精确清理 stage；OUTCOME_UNCERTAIN 按前述确认流程保留；Success 清理 cache，清理失败只显示 warning。

## 6. Activity 与导航

- TransferViewModel 及 parser/cache/repository/recent 由 Hilt application graph 构建，`SavedStateHandle` 只保存脱敏摘要。
- Activity 在 Root Granted 后根据 transfer state 选择普通 ISaverHomeScreen 或 ShareSavePickerScreen。
- 保存模式沿用 LocationHome/Browser 导航数据，但使用独立 scaffold，避免在普通主页堆叠条件分支。
- onNewIntent 交由 ViewModel generation 管理：publish 边界前旧异步结果不得覆盖新请求；publish 边界后的旧终态具有优先级，必须完成 reconciliation 后才能激活排队的新请求。

## 7. 测试与真机验收

- Parser JVM：API 29/33/35 的 SEND/VIEW、ClipData 单项/多项、坏 Parcelable、冲突 Uri、file/http、空白名称、空/负大小、MIME 回退、Provider 异常、取消与 2 秒超时。
- Manifest instrumentation：SEND 与 PDF content VIEW 能 resolve iSaver；file/http VIEW 与 SEND_MULTIPLE 不能 resolve。file/http SEND 可能被 MIME-only SEND filter 匹配，但必须由 Parser 在运行时拒绝。
- ViewModel：Root 门禁期间立即缓存、目录能力、双编辑框与多段扩展重名、publish 前后取消/新 Intent、显式重试、Root 失效、cache/目标身份变化、不确定结果、最近项目和 generation。
- Compose：全屏 picker、隐藏三标签、TopBar 对齐、文件弱化、双输入框、保存能力、进度/失败/不确定状态，semantics 不包含 Uri/cache。
- 小米 9：测试 FileProvider 分别发送 SEND 与 VIEW；验证冷启动、暖启动、旋转、进程重建、中文/空格/引号/重名/大文件/取消/空间不足。
- 最后人工从微信打开一份用户选择的 PDF，只保存到专用测试目录，不修改微信源文件。

## 8. 非目标

- 不预览或编辑 PDF 内容。
- 不实现多文件分享、标签、云盘或非 Root fallback。
- 不伪造 iPhone 状态栏或 Home 指示条。

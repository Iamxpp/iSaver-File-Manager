# iSaver iOS Files 高保真、本地归档与远程服务器设计

## 目标

iSaver 在 Android 系统边界内高保真复刻 iOS Files 的信息层级与主要交互，同时保持 Root-only、安全文件操作和可测试架构。开发顺序为本地体验优先，远程服务器最后实现。

## 已确认的产品决策

- 底部固定“最近项目 / 视图 / 浏览”。
- “视图”展示用户添加的 Root 路径，文件夹名称使用用户备注；备注不改变磁盘名称。
- “浏览”从 `/` 开始展示完整 Root 文件系统。
- 右上角三点菜单提供列表/图标、名称/种类/日期/大小排序、新建文件夹、压缩文件和连接服务器。
- 创建压缩包只支持 ZIP；浏览和解压支持 ZIP、TAR、TAR.GZ、7Z、RAR；RAR 不创建。
- 远程支持 SFTP、FTPS、普通 FTP，并安排在所有本地功能之后。
- 远程首版支持浏览、上传、下载、新建目录，以及需要二次确认的重命名和删除；不做自动同步。

## UI 设计

大标题、搜索框、文件夹蓝色、白色列表、细分隔线、三列图标网格、底部标签栏和右上菜单按参考图高保真实现。应用保留 Android 状态栏、导航栏和无障碍语义，不伪造 iPhone 状态栏或 Home 指示条。列表与网格共享同一数据状态，切换不重新读取目录；排序和显示偏好由 DataStore 持久化。

## 本地数据与导航

Room 保存自定义路径、备注和最近项目。`Views` 只展示逻辑入口；`Browse` 的根为 `/`。打开任何入口都调用 `BrowserViewModel.openRoot(path, title)`，目录内返回逐级回退，到入口根时回到来源标签。

## Root 安全

UI、Composable 和 ViewModel 不构造 Shell 命令。所有写操作通过 typed `RootFileSystem` 和共享 Root coordinator。符号链接目录不可作为新建、保存、压缩或解压目标。写入使用目标目录临时文件、校验和原子 rename；无法确认结果时返回 `OUTCOME_UNCERTAIN`。

## 压缩归档

归档模块提供 typed create/inspect/extract API。创建 ZIP 和解压均流式处理、报告进度、支持取消并清理临时文件。归档条目必须保持在目标目录内，拒绝绝对路径、`..`、NUL、符号链接逃逸和异常展开量；设置条目数、展开大小和压缩比限制。

## 远程服务器

SFTP、FTPS、FTP 使用统一 `RemoteFileSystem` 接口和协议适配器。SFTP 主机密钥与 FTPS 证书默认严格校验；FTP 连接前展示明文风险。Room 保存非秘密配置，秘密使用 Android Keystore 加密。上传下载使用临时文件、进度、取消、失败清理和有限断线重试；危险操作不自动重放。

## 测试与验收

所有行为严格 RED→GREEN。自动门禁包含 unit、Compose UI、Lint、assemble 和适用的 instrumentation。小米 9 使用 serial `d51f42ac`，每条 ADB 命令显式指定设备；APK 通过 Root `pm install -r` 安装。UI 使用 ADB 截图和 UIAutomator 与参考图对比。文件操作只使用专用测试目录，不修改真实微信数据。

## 里程碑

1. 完成 M2 三标签、位置、Root 浏览、显示与排序及真机视觉验收。
2. 完成 M3 ACTION_SEND、最近项目和安全另存。
3. 完成 M4 ZIP 创建与多格式安全浏览/解压。
4. 完成 M5 视觉、性能、兼容和开源准备。
5. 最后完成 M6 SFTP、FTPS、FTP。

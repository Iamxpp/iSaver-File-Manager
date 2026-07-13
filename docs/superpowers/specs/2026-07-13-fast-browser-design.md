# iSaver 快速 Root 目录浏览与统一导航栏设计

**日期：** 2026-07-13
**状态：** 已获用户批准
**目标设备：** 小米 9，Android 11 / API 30，Root

## 1. 目标

- 所有真实目录层级使用与“视图”一致的 56dp 紧凑导航栏：左侧返回槽、几何居中标题、右侧三点菜单同一水平线。
- 搜索栏紧贴导航栏下方，不再使用两行大标题。
- 根治目录冷加载时长，而不是只隐藏“正在读取目录”转圈。
- 200 个条目的普通目录在小米 9 上首批可见 P95 小于 500ms；缓存命中小于 100ms。
- 保留特殊文件名、符号链接和 Root 写入前复核等安全边界。

## 2. 已确认根因

当前 LibsuRootFileSystem 的单次 list shell job 对每个目录项运行多个 stat、printf 和 base64 子进程。小米 9 枚举根目录的 44 项耗时 3.74 秒；strace 记录 281 次 clone、282 次 execve 和 561 次 wait4，约为每项 6.4 次进程执行。Compose 不是主要瓶颈。

其他放大因素：

- BrowserViewModel 等完整 list 和额外 parent stat 都结束后才展示结果。
- 当前所谓分页是全量读取、解析和排序后的内存切片。
- Root shell 全局 mutex 与启动时位置探测、浏览预读产生排队。
- 加载新路径时清空旧列表，造成明显空白转圈。

## 3. 方案选择

### 3.1 采用：固定 native list-dir + 内存快照

扩展现有非驻留、固定子命令 native helper，增加只读 list-dir：

1. open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)。
2. fdopendir/readdir 单进程枚举。
3. 对每项使用 fstatat(dirfd, name, AT_SYMLINK_NOFOLLOW)。
4. 在 C 内完成 Base64 编码，输出固定版本 TSV 协议，不启动条目级子进程。
5. 父目录元数据随同返回，取消 Browser 额外 parent stat。

协议首行为：

    ISAVER_LIST_V1	<parent-device>	<parent-inode>	<readable>	<writable>

后续每项为：

    <name-base64>	<path-base64>	<type>	<size-or-dash>	<mtime-or-dash>	<readable>	<writable>	<symlink>

输出限制为最多 100,000 项、单字段 1 MiB、总协议 64 MiB；越界返回 typed failure。单条无法 stat 时输出 other/不可用状态或跳过并计数，不得使整页崩溃。

### 3.2 不采用的方案

- **批量 shell/find：** 改动小，但 Android toybox 差异和换行等特殊文件名协议不可靠。
- **仅做缓存：** 可改善回访，无法解决首次进入大目录的 O(N) 子进程开销。

## 4. 快照与展示

- DirectorySnapshotCache 为进程内 LRU，最多 16 个目录，TTL 2 秒，不持久化。
- 缓存命中时立即展示旧快照并标记 refreshing=true；后台刷新成功后原位替换。
- 无缓存时延迟 120ms 才显示小型进度提示，快速目录不会闪烁转圈。
- 导航 generation 继续阻止旧路径结果覆盖新路径。
- App 启动不再无条件预读内部存储；只有用户实际进入 Browse 或位置时加载。
- 当前页面的排序必须基于完整快照，不能对独立分页分别排序。

## 5. 统一导航栏

FilesPageHeader 是唯一产品页面头部：

- FilesTopBar 固定 56dp。
- 左右各使用相同宽度 action slot，标题相对屏幕几何居中。
- 根层左槽留空，二级目录显示返回。
- 右侧显示 overflow。
- FilesSearchField 仅保留水平 16dp 与底部 8dp 间距。
- 产品代码停止使用 FilesLargeTitleHeader。

## 6. 测试与验收

- native parser 单测覆盖空格、中文、引号、换行、前导短横线、非法 UTF-8、symlink 和 stat 失败。
- 四 ABI 构建。
- Compose bounds 测试断言标题、返回、overflow 垂直相交且标题中心等于 bar 中心。
- 小米 9 在专用 /data/local/tmp/isaver-perf 建立 0/50/200/1000 项夹具，不触碰微信数据。
- 记录 cold/warm 20 次 P50/P95：helper、parse/sort、首批可见和全量完成。

## 7. 安全边界

- list-dir 只读且固定子命令，不接受脚本。
- 列表中的 writable 仅作 UI 提示；创建、保存、压缩等写操作仍重新 canonical/stat/inode 校验。
- 不解析 ls，不跟随未知 symlink，不把原始路径写入日志。

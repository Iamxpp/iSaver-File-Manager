# M3 ACTION_SEND 分享另存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接收第三方应用分享的单个 `content://` 文件，在 iSaver 三标签位置界面选择 Root 可写目录，并通过应用私有缓存、目标临时文件和原子完成安全保存，同时记录最近项目。

**Architecture:** `ShareIntentParser` 只解析 Intent/Uri 权限和展示元数据；`IncomingFileCache` 将 ContentResolver 流复制到 UUID 私有缓存；`RootFileTransferRepository` 通过扩展后的 typed `RootFileSystem` 执行目标探测、临时复制、大小校验和原子 rename。`TransferViewModel` 管理进度/取消/恢复，UI 复用 Home/Browser 并在可写目录显示保存动作。

**Tech Stack:** Kotlin、Coroutines/Flow、ContentResolver、Room、Compose、libsu typed Root operations、JUnit/Robolectric/AndroidTest/ADB。

---

### Task 1: 分享 Intent 解析与来源元数据

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/share/IncomingShare.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/share/ShareIntentParser.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/share/ShareIntentParserTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] RED：仅接受 ACTION_SEND 单个 EXTRA_STREAM `content://`；拒绝缺 Uri、file://、多文件；读取 DISPLAY_NAME/SIZE/MIME；无名回退“未命名文件”并保留可推断扩展名；不解析真实路径。
- [ ] GREEN：实现 parser 和 SEND/*/* filter，权限错误返回 typed failure。
- [ ] full/lint/assemble，提交 `feat: parse incoming shared files`。

### Task 2: 应用私有 UUID 缓存

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/IncomingFileCache.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/transfer/IncomingFileCacheTest.kt`

- [ ] RED：流式复制、UUID `.tmp`、进度、取消、IOException、空间不足、长度不匹配、失败清理；不使用原始文件名作为缓存名。
- [ ] GREEN：IO dispatcher、固定 cache/incoming 目录、finally 清理 API。
- [ ] 提交 `feat: cache shared content safely`。

### Task 3: typed Root 文件传输原语

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/data/root/RootFileSystem.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/data/root/LibsuRootFileSystem.kt`
- Create: `app/src/main/cpp/isaver_fs_helper.c`
- Create: `app/src/main/java/com/iamxpp/isaver/data/root/RootTransferHelper.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/com/iamxpp/isaver/data/root/LibsuRootFileSystemTest.kt`

- [x] RED：目标目录原始路径非 symlink/identity/canonical复核；在目标父目录原子创建 Root-owned `0700` `.isaver-stage-<uuid>` 并绑定身份；源文件 inode/regular-file/size 绑定；stage 替换或权限变化拒绝；最终名称竞争返回 `ALREADY_EXISTS` 且不覆盖；复制空间不足；取消/超时按 stage inode 精确清理；无法确认最终副作用时返回 `OUTCOME_UNCERTAIN`。
- [x] GREEN：NDK 构建固定 `prepare-stage`、`copy-publish`、`remove-stage` 子命令 helper，使用父目录/stage 目录 FD、`O_DIRECTORY/O_NOFOLLOW/O_EXCL`、Root owner 与 `0700` 校验、`renameat2(RENAME_NOREPLACE)`、精确 inode 清理；复制与发布在单次 helper 进程中持有已验证 FD。`copy-publish` 使用固定 `/system/bin/timeout` 参数和一次性独立 Root shell 做有界故障隔离，超时/被杀统一进入 `OUTCOME_UNCERTAIN` reconciliation，不占用应用全局 Root mutex。Kotlin 只暴露 cohesive typed staging/transfer API，删除 split `copyFromAppCache`/`moveTemporary`/`removeTemporary`，无通用 shell。
- [x] 小米9专用测试目录验证，提交 `feat: add atomic root transfer operations`。

### Task 4: 重名策略与 Transfer Repository

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/TargetNameResolver.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/RootFileTransferRepository.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/transfer/TargetNameResolverTest.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/transfer/RootFileTransferRepositoryTest.kt`

- [ ] RED：`a.txt`→`a (1).txt`、无扩展/多点/隐藏文件、最多尝试；两阶段状态；Root失效不重放；取消双端清理；NO_SPACE；结果不确定。
- [ ] GREEN：进度 Flow 与最终保存结果。
- [ ] 提交 `feat: save shared files to root locations`。

### Task 5: 最近项目持久化

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/data/local/RecentItemEntity.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/data/local/RecentItemDao.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/data/local/ISaverDatabase.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/recent/RecentRepository.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/recent/RecentRepositoryTest.kt`

- [ ] RED：成功访问/保存 upsert、最近优先、上限、路径备注、失效保留状态；失败传输不记录。
- [ ] GREEN：Room migration v1→v2 与 schema。
- [ ] 提交 `feat: persist recent file activity`。

### Task 6: TransferViewModel 与分享导航

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/TransferUiState.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/transfer/TransferViewModel.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/MainActivity.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/transfer/TransferViewModelTest.kt`

- [ ] RED：普通启动/分享启动；来源摘要；仅可写非symlink目录启用保存；进度；取消；重试；成功最近记录；Activity重建恢复Uri元数据但不持久化Uri权限假象。
- [ ] GREEN：Activity将 Intent 交 parser，Home进入保存模式。
- [ ] 提交 `feat: add share-to-save workflow state`。

### Task 7: 分享保存 Compose UI

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeScreen.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/BrowserScreen.kt`
- Create: `app/src/androidTest/java/com/iamxpp/isaver/ui/ShareSaveFlowTest.kt`

- [ ] RED：来源文件摘要、保存按钮能力、进度/取消、成功名称、失败/空间不足、不确定结果；不出现真实Uri/缓存路径。
- [ ] GREEN：复用三标签和Browser，无中转页。
- [ ] 真机 Root instrument，提交 `feat: add share save interface`。

### Task 8: M3 真机验收

- [ ] 用 ADB 构造 content provider 或测试应用 ACTION_SEND。
- [ ] 保存到 `/storage/emulated/0/isaver-test`，覆盖中文名、空格、引号、重名、大文件、取消、空间错误模拟。
- [ ] 检查 app cache 与目标目录无 `.isaver-*.tmp` 残留。
- [ ] 验证最近项目更新、无 FATAL/ANR，运行全量门禁和双审。

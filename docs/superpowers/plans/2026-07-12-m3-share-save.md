# M3 ACTION_SEND 分享另存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接收第三方应用分享的单个 `content://` 文件，在 iSaver 三标签位置界面选择 Root 可写目录，并通过应用私有缓存、目标临时文件和原子完成安全保存，同时记录最近项目。

**Architecture:** `ShareIntentParser` 只解析 Intent/Uri 权限和展示元数据；`IncomingFileCache` 将 ContentResolver 流复制到 UUID 私有缓存；`RootFileTransferRepository` 通过扩展后的 typed `RootFileSystem` 执行目标探测、临时复制、大小校验和原子 rename。`TransferViewModel` 管理进度/取消/恢复，UI 复用 Home/Browser 并在可写目录显示保存动作。

**Tech Stack:** Kotlin、Coroutines/Flow、ContentResolver、Room、Compose、libsu typed Root operations、JUnit/Robolectric/AndroidTest/ADB。

---

### Task 1: 分享 Intent 解析与来源元数据

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/share/IncomingShare.kt`
- Create: `app/src/main/java/com/isaver/filemanager/share/ShareIntentParser.kt`
- Test: `app/src/test/java/com/isaver/filemanager/share/ShareIntentParserTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] RED：仅接受 ACTION_SEND 单个 EXTRA_STREAM `content://`；拒绝缺 Uri、file://、多文件；读取 DISPLAY_NAME/SIZE/MIME；无名回退“未命名文件”并保留可推断扩展名；不解析真实路径。
- [ ] GREEN：实现 parser 和 SEND/*/* filter，权限错误返回 typed failure。
- [ ] full/lint/assemble，提交 `feat: parse incoming shared files`。

### Task 2: 应用私有 UUID 缓存

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/transfer/IncomingFileCache.kt`
- Test: `app/src/test/java/com/isaver/filemanager/transfer/IncomingFileCacheTest.kt`

- [ ] RED：流式复制、UUID `.tmp`、进度、取消、IOException、空间不足、长度不匹配、失败清理；不使用原始文件名作为缓存名。
- [ ] GREEN：IO dispatcher、固定 cache/incoming 目录、finally 清理 API。
- [ ] 提交 `feat: cache shared content safely`。

### Task 3: typed Root 文件传输原语

**Files:**
- Modify: `app/src/main/java/com/isaver/filemanager/data/root/RootFileSystem.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/data/root/LibsuRootFileSystem.kt`
- Create: `app/src/main/cpp/isaver_fs_helper.c`
- Create: `app/src/main/java/com/isaver/filemanager/data/root/RootTransferHelper.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/com/isaver/filemanager/data/root/LibsuRootFileSystemTest.kt`

- [x] RED：目标目录原始路径非 symlink/identity/canonical复核；在目标父目录原子创建 Root-owned `0700` `.isaver-stage-<uuid>` 并绑定身份；源文件 inode/regular-file/size 绑定；stage 替换或权限变化拒绝；最终名称竞争返回 `ALREADY_EXISTS` 且不覆盖；复制空间不足；取消/超时按 stage inode 精确清理；无法确认最终副作用时返回 `OUTCOME_UNCERTAIN`。
- [x] GREEN：NDK 构建固定 `prepare-stage`、`copy-publish`、`remove-stage` 子命令 helper，使用父目录/stage 目录 FD、`O_DIRECTORY/O_NOFOLLOW/O_EXCL`、Root owner 与 `0700` 校验、`renameat2(RENAME_NOREPLACE)`、精确 inode 清理；复制与发布在单次 helper 进程中持有已验证 FD。`copy-publish` 使用固定 `/system/bin/timeout` 参数和一次性独立 Root shell 做有界故障隔离，超时/被杀统一进入 `OUTCOME_UNCERTAIN` reconciliation，不占用应用全局 Root mutex。Kotlin 只暴露 cohesive typed staging/transfer API，删除 split `copyFromAppCache`/`moveTemporary`/`removeTemporary`，无通用 shell。
- [x] 小米9专用测试目录验证，提交 `feat: add atomic root transfer operations`。

### Task 4: 重名策略与 Transfer Repository

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/transfer/TargetNameResolver.kt`
- Create: `app/src/main/java/com/isaver/filemanager/transfer/RootFileTransferRepository.kt`
- Test: `app/src/test/java/com/isaver/filemanager/transfer/TargetNameResolverTest.kt`
- Test: `app/src/test/java/com/isaver/filemanager/transfer/RootFileTransferRepositoryTest.kt`

- [x] RED：纯函数 `TargetNameResolver` 锁定原名、无扩展/多点/隐藏文件、UTF-8 255 字节和最大尝试边界；Repository 锁定仅 `ALREADY_EXISTS` 推进候选名、Root 失效不重放、取消清理、NO_SPACE 与结果不确定语义。
- [x] GREEN：使用文件专用 `EntryName`；`RootFileTransferRepository` 直接调用 cohesive `RootFileSystem.transferFromAppCache`（Root 层内部管理私有 stage），输出 `Resolving`、`Publishing(candidate, attempt)` 与单一 `Success`/`Failure` terminal。确定成功、确定失败和普通取消清理 app cache；`OUTCOME_UNCERTAIN` 保留 cache；清理失败只附加 warning，不覆盖发布结果。不伪造字节百分比，消息不得包含 content Uri、cache 或目标绝对路径。
- [x] 提交 `feat: save shared files to root locations`。

### Task 5: 最近项目持久化

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/data/local/RecentItemEntity.kt`
- Create: `app/src/main/java/com/isaver/filemanager/data/local/RecentItemDao.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/data/local/ISaverDatabase.kt`
- Create: `app/src/main/java/com/isaver/filemanager/recent/RecentRepository.kt`
- Test: `app/src/test/java/com/isaver/filemanager/recent/RecentRepositoryTest.kt`

- [x] RED：成功访问/保存 upsert、最近优先、100 项明确上限、路径备注、失效保留状态；失败传输没有写入 API，由调用方仅在确认成功后调用 `recordAccess`/`recordSaved`。
- [x] GREEN：`recent_items` 以调用方提供的 canonical `RootPath` 字符串为主键，DAO 在单事务内 upsert/裁剪；Room migration v1→v2、schema 2 与小米 9 instrumentation migration 测试完成。
- [x] 提交 `feat: persist recent file activity`。

### Task 6: TransferViewModel 与分享导航

**Files:**
- Create: `app/src/main/java/com/isaver/filemanager/transfer/TransferUiState.kt`
- Create: `app/src/main/java/com/isaver/filemanager/transfer/TransferViewModel.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/MainActivity.kt`
- Test: `app/src/test/java/com/isaver/filemanager/transfer/TransferViewModelTest.kt`

- [ ] RED：普通启动/分享启动；来源摘要；仅可写非symlink目录启用保存；进度；取消；重试；成功最近记录；Activity重建恢复Uri元数据但不持久化Uri权限假象。
- [ ] GREEN：Activity将 Intent 交 parser，Home进入保存模式。
- [ ] 提交 `feat: add share-to-save workflow state`。

### Task 7: 分享保存 Compose UI

**Files:**
- Modify: `app/src/main/java/com/isaver/filemanager/ui/ISaverHomeScreen.kt`
- Modify: `app/src/main/java/com/isaver/filemanager/ui/BrowserScreen.kt`
- Create: `app/src/androidTest/java/com/isaver/filemanager/ui/ShareSaveFlowTest.kt`

- [ ] RED：来源文件摘要、保存按钮能力、进度/取消、成功名称、失败/空间不足、不确定结果；不出现真实Uri/缓存路径。
- [ ] GREEN：复用三标签和Browser，无中转页。
- [ ] 真机 Root instrument，提交 `feat: add share save interface`。

### Task 8: M3 真机验收

- [ ] 用 ADB 构造 content provider 或测试应用 ACTION_SEND。
- [ ] 保存到 `/storage/emulated/0/isaver-test`，覆盖中文名、空格、引号、重名、大文件、取消、空间错误模拟。
- [ ] 检查 app cache 与目标目录无 `.isaver-*.tmp` 残留。
- [ ] 验证最近项目更新、无 FATAL/ANR，运行全量门禁和双审。

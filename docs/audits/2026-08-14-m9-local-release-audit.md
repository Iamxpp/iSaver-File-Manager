# iSaver M9 本地发布风险与验收审计

> 审计日期：2026-08-14
> 目标基线：0.5.x（M8 + M9）
> 结论：本地发布门禁通过；远程入口仍冻结，等待独立 M10 授权与验收。

## 1. 发布结论

- `ReleaseFeatures.remoteServers = false`，JVM 测试固定该常量；API 29、33、35 Emulator 的 Compose UI 验收均确认不生成“连接服务器”入口。
- 小米 9（Android 11 / API 30）完成 23 组串行 instrumentation、共享存储可见工作流、Root 列表性能和冷启动检查。
- 648 个 JVM 测试、Lint、四 ABI native、Debug APK 与 AndroidTest APK 通过。
- API 29、33、35 本轮重新完成无 Root 兼容矩阵；这些结果只证明安装、Root 阻断和 UI 生命周期，不证明对应版本的 Root 文件操作。

## 2. 路径风险分级

| 等级 | 范围 | 发布策略 |
| --- | --- | --- |
| R0 | `/storage/emulated/0/...` | 允许本地操作；删除优先进入回收站；替换必须可恢复。 |
| R1 | `/storage/emulated/0/Android/data/...` | 允许 typed 操作，继续执行 identity 与可写性复核。 |
| R2 | `/data/user/...`、`/data/data/...` | 允许受支持操作；权限放宽必须增强确认，不提供批量永久删除捷径。 |
| R3 | `/data/adb`、`/system`、`/vendor`、`/product`、`/boot` | 写操作阻断；只读浏览不把路径当作可信 identity。 |
| R4 | `/proc`、`/sys`、`/dev`、`/apex` | 写操作阻断；symlink 与特殊项目不进入普通文件操作。 |

保护规则采用路径边界匹配，`/system-old` 不会被误判为 `/system` 后代。所有写入还必须在提交前重读 original/canonical 父目录、device/inode、类型和符号链接状态。

## 3. 操作风险矩阵

| 能力 | 提交边界与失败策略 | 重放/恢复策略 | M9 证据 |
| --- | --- | --- | --- |
| 默认打开、打开方式、分享、导出 | Root 文件先进入私有 cache，再签发一次性只读 `content://`；不暴露 Root 路径 | 导出失败撤销 token；消费后失效；不重放旧授权 | 打开、分享、Provider、微信分享目标专项通过 |
| 复制 | 来源与目标父目录 identity 绑定；stage 完整写入、校验后无覆盖发布 | 仅明确 `ALREADY_EXISTS` 可换名；不确定结果停止 | 文件和目录复制、共享存储流程通过 |
| 移动 | 同盘 `RENAME_NOREPLACE`；跨盘先发布目标再精确删源 | 删源失败为部分成功；不确定时同时核对两端 | 文件/目录移动专项通过 |
| 替换 | 仅共享存储；旧目标先进入带 identity 的回收记录 | 发布失败无覆盖恢复；恢复失败为 `OUTCOME_UNCERTAIN` | 可恢复替换回归纳入 JVM/Root 门禁 |
| 重命名 | 同父目录无覆盖 rename；批量使用随机临时名处理交换与循环 | 确定失败逆序补偿；补偿失败停止人工核对 | 批量计划和 Root rename 回归通过 |
| 新建文件、文件夹 | 父目录复核；`O_EXCL|O_NOFOLLOW` 或固定 mkdir 原语 | 超时或复核异常不重放，刷新确认 | Root create 专项通过 |
| 删除、回收、恢复 | 共享存储默认回收；永久删除绑定 exact identity 并限制递归 walker | `PENDING` 异常转 `NEEDS_REVIEW`；恢复默认无覆盖 | 数据库、回收与 Root 操作回归通过 |
| 归档、解压 | 归档读取稳定来源；解压先写 stage，再无覆盖发布；虚拟视图层禁止作为目标 | 取消清理未发布 stage；发布不确定时人工核对 | 归档 2 项、共享存储解压和可见流程通过 |
| 文本编辑 | 完整文件版本绑定；私有流写入；POSIX exchange 或受控共享存储降级 | 外部变化阻断；提交不确定不重放并保留核对信息 | `/data/local/tmp` 与共享存储专项通过 |
| Hex、比较、校验和 | typed range 分块，每块匹配同一文件版本 | 只读操作可由用户重新发起；版本变化返回不确定 | 512 MiB Hex 尾页、SHA-256 及文件工具通过 |
| 权限修改 | 单项非递归 `fchmod`；绑定旧 mode 与 identity；R3/R4、symlink、特殊位阻断 | 提交后复核异常为 `OUTCOME_UNCERTAIN`，不得自动重放 | Root 权限专项通过 |
| 深度搜索与大目录 | 单协程 typed BFS；不跟随 symlink；固定深度、项目和结果上限 | 只读任务可取消；不产生写恢复动作 | 10,000 项读取和搜索专项通过 |

## 4. 取消、进程死亡与不确定结果

- 所有持久写任务创建时固定 `OperationRecoveryPolicy.NEVER_REPLAY`。
- 应用重启把遗留的 `QUEUED/RUNNING/PAUSED/CANCELLING` 写任务转为 `NEEDS_REVIEW`，不自动继续。
- 用户取消只阻止尚未派发的项目；已进入 native 提交窗口的项目必须等待结果并完成 reconciliation。
- `OUTCOME_UNCERTAIN`、helper 超时/被杀、协议异常、发布后 identity 不一致均禁止自动重试、自动换名或自动删源。
- 回收记录的 `NEEDS_REVIEW` 项不进入恢复全部或清空批次，必须人工核对。

## 5. 性能与稳定性证据

- 10,000 项 Root 目录读取：794 ms；分页首批 200 项、第二批 400 项。
- 512 MiB 稀疏文件 Hex 尾页读取通过；SHA-256 与设备 `sha256sum` 一致，耗时 34.301 s。
- 小米 9 Root 列表基准：200 项冷 P95 105.79 ms、热 P95 94.46 ms、缓存 P95 19.00 ms、1000 项首批可见 P95 219.19 ms。
- 自然名称排序改为每条目只构造一次排序键，修复 M9 初测 1000 项首批可见 P95 554.28 ms 的回归。
- 所有测试 fixture 和临时 APK/helper 已清理；不读取或修改真实微信和用户文件。

## 6. 剩余风险

- Root 写操作完整闭环目前只在小米 9 / Android 11 / KernelSU 环境验收；其他 OEM、SELinux 策略、Root 管理器和文件系统仍需设备覆盖。
- MIUI 上完整 Compose 测试类可能卡在宿主 Activity 前台切换；发布脚本对每组 instrumentation 设置 180 秒硬超时。Compose UI 门禁在 API 29、33、35 Emulator 通过，Root 后端在小米 9 串行验证。
- 递归 chmod、owner/group 修改、APK 逆向、终端和远程文件均不属于 M8/M9 发布范围。

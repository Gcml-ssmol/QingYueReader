# 1.13.3 资料库修复与版本元数据交付

最新状态：默认系统 TEMP 下当前交付与 1.13.2 基线备份回归均已通过（`JAVA_EXIT_CODE=0`），日志为 `work/default-temp-current-results.txt`、`work/default-temp-baseline-results.txt`。测试失败后 JVM 挂起已修复；`changed-files.json` 已更新最终测试入口 SHA-256。历史重命名失败记录保留，未将其操作系统根因写成已确定。

交付时间：2026-09-24。开发副本：`work/feedback-1.13.3/library/QingYuePDF`。基线为 `outputs/1.13.2/QingYuePDF`。只修改下列 7 个文件，未改根工程、其他任务副本或 MainActivity。模型实现与专项回归已完成，包括最新默认系统 TEMP 下通过的新增备份往返情景；整包与设备验证仍未完成。下文保留初次交付遇到的环境失败及审批事实。

## 根因与用户行为

原 MainActivity 删除目录入口把 `folderId` 相同的全部资料（含最近删除）都视为占用；因此页面显示 0 个活跃资料的目录仍不能删除。LibraryIndex 正确要求资料引用的目录存在，且在 `folderId` 为空、旧 `folder` 名仍存在时会迁移重建目录。因此不能仅取消界面预判或只清空目录编号。

新增 `LibraryStore.deleteEmptyFolder(Context context, String folderId, String expectedRevision) throws IOException`。在现有写锁中重新读取索引，确认索引完整且可写、版本未过期、目标存在，拒绝仍有活跃资料或子目录的删除。将引用该目录的回收站资料的 `folderId` 与旧 `folder` 名同时改为父级；删除顶层目录时两者均清空，后续恢复到未分类。目录移除与引用修改使用同一次 AtomicFile 索引提交；不改动文档或批注载荷，不重置 `trashedAt`。资料的 `modified` 按既有整理行为推进，逻辑 revision 随索引变化。

部分记录损坏时不能可靠判断目录为空，故该删除操作额外拒绝 `damaged` 非空索引；保留原始损坏恢复机制。保存失败不返回成功、不改调用方已加载对象。没有改动 LibraryIndex 编解码、TrashManager 永久删除/恢复事务或备份格式。

## 接口接线

MainActivity 由 navigation 任务独占。已向 `/root/prepare_release_metadata` 交付上述 API 签名及只读源码路径，要求替换旧“含最近删除”预判和内存 remove/save，改为调用该 API；成功后使用返回 State 或重新 loadLibrary 刷新，失败显示 IOException message 并刷新，避免内存先删。该接线不包含在本交付 7 文件中，须与 navigation 交付一起整合。

## 文件清单

1. `app/src/main/java/cn/qingyue/pdf/LibraryStore.java`：原子删除空目录及回收站恢复目的地迁移。
2. `tests/library-manager/ManagerRegressionTest.java`：真实生产 LibraryStore/LibraryIndex/TrashManager 与桌面 Android 替身的存储回归。
3. `tests/library-manager/BackupReaderRoundTripTest.java`：增加删目录后的父级/未分类 `.qyb` 创建、恢复及回收站再恢复情景；修复桌面测试失败后 NoteStore 工作线程继续存活的问题，失败打印异常并以 exit code 1 结束。
4. `app/build.gradle`：1.13.3 / versionCode 21。
5. `app/src/main/AndroidManifest.xml`：1.13.3 / versionCode 21。
6. `build-apk.ps1`：默认文件名 `qingyue-pdf-1.13.3.apk`。
7. `README.md`：新增本轮 8 项待整合、待验证范围；历史版本记录保留，不宣称整包通过。

逐文件基线和交付 SHA-256 见同目录 `changed-files.json`，条目为 `file/before/after`。没有新增或删除生产文件。独立 Git 提交及补丁尚未生成，按总管最新要求优先交付文件和测试事实。

## 已通过验证

- `QingYuePDF/tests/library-manager/run.ps1 -BuildDirectory work/library-manager`：通过，日志 `work/library-manager-results.txt`。新增测试覆盖：文件移入回收站 → 删除嵌套空目录 → 回收站项与原载荷保留 → 恢复到父级；再次删除顶层目录后恢复到未分类，旧目录名不复活；活跃资料与空子目录保护；旧 revision 与并发恢复保护；实际文件系统 `.new` 路径被目录占用时索引写入失败，原索引字节、载荷、恢复目的地均保留，移除失败条件后可重试；局部损坏和整体损坏索引不被删除操作改写。
- `QingYuePDF/tests/library-index/run.ps1 -BuildDirectory work/library-index`：通过，日志 `work/library-index-results.txt`；原有旧格式隔离、索引写保护、v2 元数据、筛选排序回归保持通过。
- 最新单次对照：默认 `TEMP/TMP=C:/Users/xsh12/AppData/Local/Temp` 下，当前交付完整 BackupReaderRoundTripTest 与未修改的 1.13.2 基线各运行一次，均 `JAVA_EXIT_CODE=0`。当前运行到最终成功输出，因而新增 `deletedTrashFolderBackup()` 的父级/未分类归档、恢复、回收站再恢复与载荷校验均已执行通过。日志分别为 `work/default-temp-current-results.txt`、`work/default-temp-baseline-results.txt`；classes 分别为 `work/default-temp-current`、`work/default-temp-baseline`。

初次验证生成物位于本任务 `work`，给测试进程设置 TEMP/TMP 指向该任务目录保持隔离。最新对照按总管明确要求使用默认系统 TEMP，classes、诊断入口和日志仍在本任务 `work`。以上是桌面真实文件系统与生产模型验证，不代表 Android AtomicFile 或真实设备操作验证。

最新对照命令（工作目录为本任务 library，未修改进程默认 TEMP/TMP）：

```powershell
& './work/diagnose-backup-default-temp.ps1' -Variant current *> './work/default-temp-current-results.txt'
& './work/diagnose-backup-default-temp.ps1' -Variant baseline *> './work/default-temp-baseline-results.txt'
```

该诊断脚本列明生产源码和 Android 替身的编译输入；baseline 直接读取已交付 1.13.2 文件，不编辑历史产物。`work/BackupDiagnosticMain.java` 仅在历史测试异常时终止进程，正常路径执行原始测试 main。最新修复只给当前测试增加统一成功/失败出口，没有改变任何生产备份实现。

## 历史环境失败和仍未验证内容

- 初次 `QingYuePDF/tests/backup-integration/run.ps1 -BuildDirectory work/backup-integration` 编译通过，但运行在既有首个用例 `BackupReaderRoundTripTest.java:23` 保存初始索引时失败，当时尚未执行新增删目录备份情景。确切错误为 `java.nio.file.AccessDeniedException`，发生于 Android 桌面替身 `AtomicFile.finishWrite` 的 `Files.move`，路径为 `work/backup-reader-4065691060206379732/library/index-v2.json.new` → `index-v2.json`。完整栈见 `work/backup-integration-results.txt`。
- 较早一次尝试在 `BackupArchive.extractValidated` 的验证临时目录重命名处返回 `Cannot commit validated staging directory`。其日志后被同一结果文件覆盖，当前保留的是上述 AccessDeniedException；不将任何一次失败推断为已证实的路径长度问题。
- 初次按权限失败规则申请 `require_escalated` 重跑原测试；该工具请求在等待期间被中断，当时未取得成功结果。随后总管已获准进行沙箱外原路径和短路径重跑，分别在旧 `noteTextBackup` 的 restore/createSnapshot 验证暂存目录重命名处失败，见 `work/backup-integration-escalated-results.txt` 与总项目 `work/b133/results.txt`。这些历史运行仍不得标为通过。
- 尚未完整 Android API/DEX/APK 构建、签名、设备安装与界面验收；未发布。最新默认 TEMP 运行已验证新增备份情景，但不能替代 Android 真机验证。

权限重跑状态准确记录：工具返回 `aborted by user after 576.4s`，随后收到父任务中断并先交付的指令；既不是明确拒绝，也没有获准运行并成功的结果。该请求的 `sandbox_permissions` 为 `require_escalated`，理由为“允许在独立测试目录运行资料库备份回归，以验证当前被权限限制阻止的索引原子替换和临时目录重命名吗？”。

最后普通运行及权限重跑请求使用相同命令，工作目录均为 `C:/Users/xsh12/Documents/ChatGPT/笔记app/work/feedback-1.13.3/library`：

```powershell
$taskWork = Join-Path $PWD 'work'
$env:TEMP = $taskWork
$env:TMP = $taskWork
& './QingYuePDF/tests/backup-integration/run.ps1' -BuildDirectory (Join-Path $taskWork 'backup-integration') *> (Join-Path $taskWork 'backup-integration-results.txt')
```

编译输出目录：`C:/Users/xsh12/Documents/ChatGPT/笔记app/work/feedback-1.13.3/library/work/backup-integration`；当时测试临时数据与日志位于其父级 `work`。此段记录历史失败环境，最新通过对照的命令和路径见上节。

## 最新诊断结论

- 对照 diff 确认，诊断前该测试与 1.13.2 的差异只有最后新增的删除目录备份调用及方法；旧 `noteTextBackup` 语句完全未变，并在新增情景之前运行。生产 `BackupArchive.java` 与 1.13.2 字节相同。
- **确定的挂起根因**：旧测试 main 仅成功时 `System.exit(0)`；异常结束主线程后，`NoteStore.java:11` 的 `Executors.newSingleThreadExecutor()` 非 daemon 线程继续维持 JVM。现已在测试入口捕获异常、打印原栈并 `System.exit(1)`，不吞错、不更改生产执行器。
- **文件重命名失败的根因仍未确定**：原路径、短路径在获准的沙箱外仍出现错误，因此不能断言仅是沙箱或路径长度。默认系统 TEMP 下当前和历史基线均一次通过，只能确认失败未在该环境复现、且并非新增目录删除路径的必现回归。未为迁就该现象修改生产备份流程，也未循环重试。

## 自查结论与整合注意

删除的唯一提交点仍是现有索引原子保存，回收站载荷和永久删除事务没有进入本操作。新索引中不存在悬空 folderId，根目录回退也清除旧名称防止迁移重建；旧界面快照因 revision 变化无法覆写提交。请复核新 API 与 navigation 的调用方式并执行整包回归。本交付没有为测试失败更改业务存储或备份逻辑。

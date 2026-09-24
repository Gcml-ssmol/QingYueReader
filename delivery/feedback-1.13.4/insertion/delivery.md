# 1.13.4 原位 PDF 插页交付（已冻结）

基线 `outputs/1.13.3/QingYuePDF`。仅修改本任务 `QingYuePDF` 副本，20 文件清单见 `changed-files.json`，含逐文件 SHA256 before/after，无删除。版号为 **1.13.4 / code 22**。未修改 Activity、侧栏或画布文件；依赖 navigation 的租约/等待页接线与 interaction 的 dispose 防线和笔记插页 wrapper。

整合补测修复：`crash-diagnostics` 隔离 suite 编译了真实 QingYueApplication，但缺少新增恢复服务的编译依赖。新增仅该测试目录使用的 `PdfPageInsertion` 调用记录替身，并给测试 Application 增加基底 Context；测试实际调用真实 `QingYueApplication.onCreate`，断言安装了崩溃处理器、设置了当前 Application，且调用恢复启动入口恰好一次。全部生产文件保持冻结。`& ./QingYuePDF/tests/crash-diagnostics/run.ps1 -BuildDirectory ./work/crash-diagnostics-final` 已通过，原隐私、异常原因界限、崩溃优先级、提示、退出归因和默认 handler 委托语义保持，最终日志 `work/crash-diagnostics-final-results.txt`。这里验证入口接线，不把替身计作恢复成功；恢复行为仍由真实 217 断言 suite 验证。

原实现只能追加笔记页，不能改写导入 PDF 的页树。新增 `PdfPageInsertion.insertBlankPage(Context,String,int,int)`，参数为文档编号、零基插入位置 0..旧页数和预期旧页数；返回同一编号、新页索引与页数。实际 PDFBox 插入新页后重开验证页数，原页批注、书签、文本框、清空恢复侧车、OCR 文字和库内笔记来源页号同步迁移。导入身份 hash、来源附件与文档编号保留。旧阅读页位置随原页移动，UI 成功后选择新页。

`DocumentEditLease.acquire` 只做快速内存检查；正在编辑目标 PDF 或受影响笔记时拒绝插页。Activity 先结束读写再关闭租约；服务排空已发布的笔迹、阅读器与笔记快照。准备期间新增笔记引用或其他资料变化会被最终校验发现并取消本次操作，不覆盖并发保存。已运行 OCR 的旧 PDF hash 保存请求被拒绝。

文件事务使用同文件系统的持久日志与完整原件拷贝。锁顺序为 BackupManager.TRANSACTION_LOCK → TrashManager → NoteStore → ReaderStore → InkStore → LibraryStore。生成 PDF 在存储内锁之外；提交时重新验证初始快照，安装所有载荷后最后保存索引并回读逻辑 revision，先清失效内存缓存再写 COMMITTED 标记。未提交恢复原件；已提交/已回滚标记支持清理失败后的合法后续编辑，不用旧 hash 覆盖新修改。启动由 Application 后台恢复，MainActivity worker awaitRecovery；恢复 Error 或未知索引状态保持不可打开，保留原件。只在登记的规范路径范围清理安装临时文件。

兼容与边界：保留既有 reader/ink/note v3 和 .qyb 格式；笔记插页模型保持旧页内容及视口，选中新空白页。PDF 对象目标及数字内部跳转、原页标签、相邻尺寸/crop/旋转已核对。加密、限制修改或已签名 PDF 暂拒绝；无关但损坏的笔记也会保守阻止插页，因为服务需检查全库来源引用。旧 OCR 未核验的身份语义不提升为已核验内容。**应用外已复制的 v1 页号来源 URI 不会自动更新**，本轮不引入外部链接修订映射。事务需要额外磁盘空间保存原件/新件；空间不足会取消或保留恢复副本。

验证（默认系统 TEMP；未设置 TEMP/TMP 到工作区）：

- `& ./compile.ps1`：101 个生产 Java 源，真实 Android API 35 / PDFBox / OCR 依赖编译通过。日志 `work/compile-final.log` 与 `work/compile/javac.log`。
- `& ./QingYuePDF/tests/pdf-page-insertion/run.ps1 -BuildDirectory ./work/tests`：**217 断言通过**，最终日志 `work/tests/insertion-84e7986129d441cc90392be7531d1157/test.log`。真实首页/中间/末尾插页、原页对象/目录/链接/标签、reader/ink/clear/OCR/来源、实际带批注 PDF 导出及 .qyb 备份恢复、新页书写重开；journal/半安装/索引已提交/标记已写后的故障与重启；租约冲突、准备期间新增待保存来源、清理失败后新批注、恢复 Error 闭门和中断 OCR 临时文件恢复后再插页。之前 182/214 断言通过日志保留，仅后续补充导出与预审边界，未失败重试规避权限。
- `& ./QingYuePDF/tests/ocr-sidecar-v2/run.ps1 -BuildDirectory ./work/ocr`：通过，`work/ocr-results.txt`。测试使用实际文件 hash，并验证过期写入被拒绝。
- 最终独立仓库 `review-final/.git`；补丁 `insertion.patch`，便携包 `insertion.bundle`，提交编号 `release-commit.txt`。旧 `review/.git` 保留首次交付历史。`git diff --check` 与对只读 1.13.3 基线的 `git apply --check` 通过。

未进行真机或模拟器验证、未签名/发布 APK、未安装参考应用。设备大文档耗时/峰值内存、真实笔输入与渲染、侧栏布局、旋转等待恢复由整合后验收；本交付不声称完整合并回归或最终包已通过。

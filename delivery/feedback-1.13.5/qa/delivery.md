# 1.13.5 QA 交付（冻结）

独立任务目录：work/feedback-1.13.5/qa。基线 outputs/1.13.4/QingYuePDF。仅新增 29 个测试/报告文件，不改生产、版本或 tests/run-regression.ps1；后者已自动发现 run.ps1，由总管统一 allowlist 执行。

- 提交：ba5665a45212090f1542509ac92896103bbc4634
- 清单：changed-files.json；29 个条目均为新增，before=null；file 使用相对项目正斜线路径。
- 补丁：0001-test-exercise-continuous-note-production-views-and-d.patch
- Bundle：qa.bundle（独立 .git 位于本任务目录）。
- 正式报告：QingYuePDF/delivery/1.13.5-reference-qa.md。

生产入口专项：tests/note-view-qa/run.ps1，最终 134 断言通过。直接运行生产 Canvas 触控/绘制及菜单show/click；依赖替身只记录 Android 调用，不能替代设备渲染。覆盖第二页 pinch、跨页笔迹/橡皮/undo/文本图片归属、跨缝拖拽、视口恢复、横向/无限画布、防笔掌、追加插页保zoom、菜单五操作和小窗口。

命令：tests/note-view-qa/run.ps1 -ProductionDirectory <qa>/work/combined -BuildDirectory <qa>/work/view-test-final。
日志：work/view-test-final.log。另 compile.ps1 对最终合并输入的全部 107 个生产 Java 源做真实 Android 35 + PDFBox/OCR 编译通过，见 work/compile/javac.log、result.txt；没有再次打包APK。测试输入来自 continuous 2c877ee90401e2779323beb7d459ff31b701e06c 与 ui be84391e4df5c2b5aeccb8ac393e23d8b6c7ff18。work/combined 仅为测试输入，不纳入源码交付。

已复现并由原作者修复：pinch 页内/全局 y 混用；菜单最小宽高超出小窗口。pinch 回归另经刻意恢复旧行的输入变异验证，触发预期断言失败，见 work/mutation-pinch.log。最新输入恢复后 134 条全过，无新增阻断问题。

参考包身份、资源文案/布局证据及边界详见正式报告；两包只读静态分析，未安装、运行或复制代码资产。adb无设备，本机未找到可用模拟器。仍需真机验收手写笔接触、大字/分屏菜单、真实排版与图片加载、连续滚动手感；没有声称真机帧率。

撤销保持旧语义：切换页面清空undo/redo，未引入跨页历史。依赖替身不验证NoteStore磁盘、异步图片解码或平台真实窗口摆放。其余完整回归由总管集成后统一执行。


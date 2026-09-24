# 1.14.0 编辑 UI / 参考研究 / QA 交付（冻结）

基线：outputs/1.13.5/QingYuePDF。任务目录：work/feedback-1.14.0/qa。仅交付 changed-files.json 中 13 个 owned 文件；before/after 均为 SHA256，新增文件 before=null。无版本、签名、发布或根工程改动。

提交：ed64c9de177d0aa7bb2fb2e91903ea69e6c6400e  
独立基线提交：40da8f0（仅 owned 基线文件，不直接 cherry-pick 到主工程）  
补丁：0001-feat-add-local-draft-editor-operations-and-verify-pr.patch  
Bundle：editor-ui.bundle，已验证完整。独立 .git 位于本任务目录。

## 完成内容

- NoteActivity / NoteActionMenu：可滚动操作面板；套索自由/矩形、四类型筛选与批量复制/剪切/粘贴/删除/缩放/改色/置顶置底；单个文字/图片也可进入选区操作。
- NotePenPresets：最多8支本地笔盒，颜色/宽度/笔种保存、调用、移除与坏数据过滤；不改变笔记v3格式。
- 橡皮面板：局部/整笔模式。只读模式明确且调用 core.setReadOnly，禁用编辑入口，避免笔/硬件橡皮或末页长拉改文档。
- 无限画布：最高1000%与原点/适配；旧版多张无限画布保留左栏、画布i/N和真实切换管理；PDF范围文案匹配全部画布。
- 有限页/历史多画布：复制、清空、删除、调序；清空/删除显示具体目标、对象数量、可撤销性并至少保留一页。
- NotePageSidebar 保留左侧栏和单击选页，长按进入管理；新单张无限草稿显示定位入口。
- NoteMotion（creation所有权）接入菜单、工具面板、标题恢复、侧栏显现及返回转场；持续书写画布不加动画。
- IconButton 新增自绘套索/阅读/笔盒图标。无参考APK代码或资产复制。

## 协作接口

依赖 editor 的 NoteCanvasView 新API、NoteEditorOps、NoteDocument、ZoomLimits及NoteStore.imageExists；依赖 creation 的 NoteMotion.enter(View)/applyActivityTransition(Activity,boolean)。这些他组生产文件仅用于work/combined测试输入，不在QA清单中。

原 NoteActionMenu.show 五操作签名仍保留；新 showActions 支持不可用操作置灰。NotePageSidebar.Actions 新增default manage(page)，保留旧接口兼容。新 NoteEditorPresentation 将旧无限多画布导航策略与真实导出范围用于生产UI和测试。

## 验证

- 真实 SDK：./compile.ps1。111 个编辑集成输入生产 Java 源，Android API 35 + 真实PDFBox/OCR依赖，PASS（仅弃用提示）。
- 生产 View：QingYuePDF/tests/note-view-qa/run.ps1 -ProductionDirectory <qa>/work/combined -BuildDirectory <qa>/work/view-test-final。
- work/view-test-final.log：207 assertions PASS；work/compile/javac.log、result.txt。
- 新覆盖多对象真实圈选/拖移/角点缩放、颜色alpha、剪切粘贴新ID、整笔/局部擦除、复制删清页、1000%保存恢复、只读笔/橡皮/API与末页长拉（含可编辑同手势会新增的控制组）、旧3画布访问、几何轮廓反例、笔盒与禁用菜单。保留原134断言。
- 测试仍由tests/run-regression.ps1自动发现，无需修改入口。runner新增真实NoteEditorOps/NotePenPresets/NoteEditorPresentation源；Android替身补足必要方法，全部纳入13文件清单。
- qa/work/combined 是只读输入快照的汇总，不可复制回源树；生成物均未纳入交付。

## 参考证据与局限

research.md 与项目内 delivery/1.14.0-editor-reference-qa.md 同步。矩阵明确为1.13.5基线差距，最后一节另列本轮完成项和未完成清单。云记APK静态身份/资源/49个布局/13个可读根DEX已检查；业务定义受包保护，无法确认全部工具在无限分支的运行可用性。

adb无设备、未找到可用模拟器；未安装运行参考APK。生产View用Android依赖替身录制绘制命令，不是Android真正渲染、字体布局、WindowManager或硬件手感验收。NoteActivity仅真实SDK编译和源审；图片异步与磁盘文件存在性由core套件覆盖。本轮不声明全功能复制、真实笔压、云端/录音/独立图层、旋转裁剪、智能图形或笔记内搜索已实现；完整剩余项见矩阵。

主审发现的旧无限多画布不可达、只读自动添页两个兼容问题均已修复并加入回归。当前没有新增未解决阻断问题。最终完整回归与APK由总管统一执行。


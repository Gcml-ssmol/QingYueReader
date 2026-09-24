# 创建草稿 UI 阶段交付

基线 `outputs/1.13.5/QingYuePDF`，隔离工程 `creation/QingYuePDF`。最终 HEAD `1d55d6ae3f26603d222da60263ffe0bad2cc2c42`。按顺序应用 `final-patch/0001-Default-to-infinite-draft-with-tested-creation-and-m.patch`、`final-patch/0002-Animate-internal-note-jumps.patch`、`final-patch/0003-Disable-activity-transitions-when-system-animations-.patch`；bundle `creation-ui-final.bundle`。变更清单 `changed-files.json`。旧阶段补丁由最终补丁取代；集成时仅按清单的 owned 文件取代码。

创建面板首次默认无限草稿，明确区分无限草稿与分页笔记。分页仍提供六种已有尺寸，三种已有底纹在两种模式下都能用。仅成功点击创建时保存 `note_creation` 偏好：底纹、模式/尺寸及上次固定纸张尺寸；已有用户文档不迁移。名称、存储和当前文件夹继承保持原逻辑。

`NoteMotion.enter(View)` 提供 180 ms 的面板淡入微滑；重复调用会取消旧动画并恢复透明度/位移，视图移除时也恢复。`NoteMotion.applyActivityTransition(Activity, boolean)` 提供进入/返回活动的短过渡；系统 Animator、Transition、Window 任一比例为零时显式调用 `overridePendingTransition(0,0)`，保证直接切换。MainActivity 打开新建、既有笔记及来源链接内部跳转时调用进入过渡。返回过渡留给 NoteActivity owner 挂接；本任务不修改该文件。无笔迹或持续书写动画。

本机真实 Android 35 SDK 与完整依赖构建、DEX/APK 签名验证通过；最终 `NoteDialogs`、`NoteCreationChoices`、`NoteMotion` 以真实 SDK 增量 javac 编译通过。`tests/note-creation/run.ps1` 运行生产选择/提交逻辑，覆盖默认 infinite、分页尺寸和底纹偏好恢复、取消不写、空名不创建；`tests/note-motion/run.ps1` 运行生产动画 helper，覆盖重复/取消/移除、三种系统动画比例关闭时传 `0,0` 和进入/返回资源。两个脚本均接收 `BuildDirectory` 并只向其写输出。没有真机界面测试。封面、纸张颜色、横向纸张等待核心数据契约，当前面板不显示无效选项。云记普通笔记与无限草稿布局的证据边界见 `research.md`。

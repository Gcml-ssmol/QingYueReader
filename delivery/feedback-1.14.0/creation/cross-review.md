# 1.14 核心与 UI 交叉只读审查

对象：`work/feedback-1.14.0/editor/QingYuePDF` 中 NoteCanvasView、NoteDocument、NoteEditorOps；`work/feedback-1.14.0/qa/QingYuePDF` 中 NoteActivity；本任务 `creation/QingYuePDF` 中 MainActivity/NoteMotion。只读静态审查，不是设备触控验收，未修改其他任务文件。

## 发现与处理状态

1. **只读模式可能插页，已由核心与 UI owner 修复。** 初读时 NoteActivity 只把工具切成 hand，Canvas 在横向末页 ACTION_UP 满足长拖条件仍 `addPage()`。目前核心已有 `setReadOnly`、`addPage` 禁止和 `canAddPage` 的 `!readOnly` 门槛，UI 的 `applyTool()` 已调用 `canvas.setReadOnly(readOnly)`。需合并后再跑触控夹具确认。
2. **历史无限多页无法导航，已由 UI owner 修复。** 初读时 `document.infinite()` 无条件隐藏缩略图，`showPages()`转定位菜单；旧 v3 无限笔记仍可能 `pageCount()>1`。目前 UI 使用 `hasPageNavigation()` 保留多画布页码和侧栏，单页无限仍保留定位入口。需用旧文档夹具确认页 2 可达。
3. **自由套索外接矩形空白角误选，已由核心 owner 修复并复核。** 原逻辑对所有对象先用外接矩形四角命中；示例笔迹从 `(0,100)` 到 `(100,0)`，在左上角 `(0,0)` 画小套索会误选。最新 `NoteEditorOps.intersectsPolygon()` 仅对图片/文本使用矩形；矢量笔迹/图形改按实际路径点和线段求交。核心 `NoteEditorTest` 已加入斜线、箭头、矩形、圆形空白角反例。本次从隔离临时目录运行生产 `tests/note-editor/run.ps1`，`note-editor: OK` 与 `note-editor store/image: OK`。
4. **内部来源链接打开笔记没有转场，已在本任务修复。** MainActivity `handleInternalJump()` 的 `startActivity(note)` 后现已调用 `NoteMotion.applyActivityTransition(this,true)`；普通资料库打开已有同样调用。UI NoteActivity `finish()` 调用返回转场。两条入口均不延迟导航。

## 其余检查

- 套索操作在 `NoteCanvasView` 的 `finishGesture()` 将整组拖动/缩放经一次 `document.applyItems()` 提交，`NoteDocument.commit()` 增加一次 undo 快照；选择本身不写数据。对象预览和提交保持页内坐标。文本缩放同时改变框与字号；图片变换保持本笔记 image 文件名。
- 复制图片后 `pasteSelection()` 先校验 `NoteStore.imageExists(context,noteId,image)`，再生成新对象 ID；没有跨笔记文件所有权转移。复制与剪切使用当前页对象，切换页时清空选择但保留本笔记剪贴板。
- `NoteMotion.enter()` 已处理重入取消、detach 恢复及系统三种动画缩放为零；`tests/note-motion/run.ps1` 对生产 helper 运行桌面替身通过。实际帧效果仍需设备视觉验收。

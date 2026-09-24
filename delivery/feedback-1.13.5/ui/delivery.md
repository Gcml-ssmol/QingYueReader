# Android 笔记 UI 独立交付

基线：`outputs/1.13.4/QingYuePDF`。工作副本：`ui/QingYuePDF`。独立审查提交：`f7de381fad32cb6cd94c6ab32eb9e47ea6d3b655`、`d1f93fc578aa8c777930318cc680254671663038`、`be84391e4df5c2b5aeccb8ac393e23d8b6c7ff18`；补丁 `0001-Compact-note-actions-and-continuous-page-controls.patch`、`0002-Fix-action-placement-and-viewport-callback.patch`、`0003-Bind-viewport-callbacks-and-expose-placement-regress.patch`，bundle `note-ui.bundle`。按顺序应用三个补丁；不应用其基线提交。

## 行为

- 右上角操作从系统大列表换为紧凑内容高度面板。可用空间足够时靠近右上角按钮，不足时居中；窄窗口限宽、横屏/大字体限高并可滚动。五项均接实际来源、全页 PDF、当前页 PNG、尾部追加页和设置操作。奶油橙与经典深色使用当前 AppColors。
- 页面缩略图栏保留当前页前/后插入，增加固定底部“添加草稿纸”尾部追加。最左工具栏缩略图按钮不变。
- 连续浏览跨页时通过 `Host.viewportPageChanged(int)` 只更新页码、可见缩略图选中态及撤销状态；`Host.viewportSettled()` 在手势完成后同步资料库页码。显式选页与插页仍使用 `pageChanged()`，450 ms 防抖合并其写入；暂停活动时画布保存成功后同步页数和页码。两个新回调由连续画布任务加入 `NoteCanvasView.Host`，集成时必须同时合入。
- 设置中页面模式明确显示“纵向连续 / 左右翻页”。旧文档、笔迹存储和导出格式无改动。

## 验证与边界

使用本机 Android 35 SDK、真实依赖和项目 `build-apk.ps1` 在 `QingYuePDF/work/ui-build` 完整编译、DEX 打包与 APK 签名验证通过；最终回调以连续任务的 `NoteCanvasView`、`NotePageLayout` 和本任务 `NoteActivity` 联合真实 SDK 编译通过。窗口定位使用一次性 `WindowManager.LayoutParams` 应用重力、坐标和 0.18 暗层；以可见框计算状态栏及分屏偏移，并有空可见框回退。`tests/note-action-menu/run.ps1` 可独立运行纯 Java 定位测试，已覆盖锚点下方、小窗口居中和边界。未连接真机；未做设备触控与视觉截图验收。APK 为独立测试构建，非发布包。需在连续画布集成后做真机竖屏、横屏、大字体、经典深色和跨页书写检查。

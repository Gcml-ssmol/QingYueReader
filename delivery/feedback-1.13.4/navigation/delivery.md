# 1.13.4 导航与插页 UI 交付

基线：`outputs/1.13.3/QingYuePDF`。本组只修改 `work/feedback-1.13.4/navigation/QingYuePDF`；未改根项目、版号、签名或发布目录。独立提交：`5e24ec50edd3b3fa71aee65950604da2143100b0`（分支 `codex/navigation-1.13.4`）。

## 行为与根因

原 PDF 页面栏没有文档中间插入空白页的入口，笔记只追加末页。现在 PDF 和笔记缩略图栏底部都有可见“＋ 插入空白页”，长按某页亦可选择；窄屏导航提供相同入口。位置对话框明确当前页、前/后相邻页，默认“此页之后”。例如当前显示 12 / 365，选择默认后插入，选中新第 13 页，旧第 13 页由底层迁移为第 14 页。

原缩略图入口分散、PDF 的 `pages` 图标退回默认圆形，paper 右下仍是四格按钮。两编辑器现统一为矩形外框加左侧四分之一分栏图标，固定在画笔工具条最左侧、横向滚动容器之外；有展开/收起无障碍标题与选中态。PDF 右下旧四格入口已移除，目录/书签仍能从侧栏及窄屏导航访问。既有 paper/classic 样式、窄屏弹出导航、平板可收合侧栏决策保留。

侧栏原 `getView` 每次创建整行。现 PDF/笔记都复用卡片、预览、页码和边框；PDF 当前页改变只更新可见行选择状态。异步缩略图绑定核验页码、provider 世代和 holder 请求 token，防止快速滚动或同页重绑时串图。缓存沿用原 provider 安全关闭规则，不主动回收已显示 bitmap；NotePagePreviewView 仅新增换绑页码接口，绘制行为未改。

## 生命周期与接口

- PDF `MainActivity` 在共享 worker 上 acquire `DocumentEditLease` 后建立阅读器。acquire 失败显示可返回资料库的错误页，不读取编辑载荷。异步返回检查 Activity generation 和文档对象，过期结果立即释放租约。
- 插页入口拒绝 `ReaderActions.exporting()`，对话框正按钮去抖，文档级任务表防止重入。开始后销毁当前阅读器/侧栏/导航，显示不可重复操作的明确等待页；本窗口返回动作在插页期间提示等待。
- `releaseView` 先触发画布最终 stage/save，再关闭 ReaderActions；后台队列等待其 IO executor 完全结束，之后关闭租约。排空不在 UI 线程。PdfReaderView 使用相同 worker，既有 InkStore/load/save 排在租约释放之前；interaction 所有者已确认 dispose 后主线程回调不会再 stage，独立 detail 渲染仅只读 PDF/生成 bitmap。
- 后台调用 insertion 提供的 `PdfPageInsertion.insertBlankPage(Context,String,int,int)`。成功和失败都重新读取索引，重开最新 LibraryItem 并重新 acquire；成功选中 `Result.insertedPage`。弱引用 owner 与文档身份检查避免结果投向另一文档；Activity 重建后通过保留任务接回结果。暂停时跳过等待期间的旧索引保存。启动 worker 先 `PdfPageInsertion.awaitRecovery(Context)`。
- NoteActivity 在 `NoteStore.load` 前 acquire，`canvas.dispose` 后 close。笔记调用 interaction 的 `NoteCanvasView.insertPage(int)`，由其调用 insertion 的模型接口并持久化、通知页数。本组不复制或修改这些跨组实现。
- 新增纯逻辑 `PageInsertionPosition` 负责经过校验的插入索引/位置说明，`ThumbnailBinding` 负责回收行请求身份。

## 变更清单

`changed-files.json` 共 13 项，每项包含相对项目路径、发布基线 SHA256 和交付 SHA256；新增文件 before 为 null。没有删除文件。UI 源码为 MainActivity、NoteActivity、ReaderActions、ReaderNavigationDialog、IconButton、PdfPageSidebar、NotePageSidebar、NotePagePreviewView，以及 PageInsertionDialog、PageInsertionPosition、ThumbnailBinding。另有 `tests/page-insertion-ui` 两文件。

补丁 `navigation.patch`，独立仓库 `.git`，便携提交包 `navigation.bundle`。补丁已对只读发布基线执行 `git apply --check` 通过，未应用到发布目录。

## 验证

- `tests/page-insertion-ui/run.ps1`：PASS，10,392 断言，覆盖用户 12/365 例子、首页/末页/单页、前后位置、无效页数及越界、回收行换页/同页重绑/关闭/重开世代，以及窄屏布局决策。
- `tests/navigation-layout/run.ps1`：PASS，窄屏弹层、平板 112–168 dp 侧栏和主阅读区宽度预算。
- `tests/thumbnail-lifecycle/run.ps1`：PASS，已发布 bitmap 在淘汰/关闭后仍有效、打开失败和晚到回调有界。
- `tests/navigation-smoke/run.ps1`：PASS，命名/GoTo 目标、嵌套目录、损坏标签/PDF、循环目录。
- `compile-android.ps1`：PASS，104 个生产 Java 源文件使用真实 Android API 35 和随包依赖编译，资源编译/链接通过。最终输出 `work/android-compile-20260924-204921`，含 javac.log、sources.json（逐源路径/hash）与资源链接检查包。
- 编译脚本只读选用 insertion/interaction 相对 1.13.3 有变化的源码验证真实跨组接口；这些源码不计入本组交付，最终仍需总管完整整合回归。

没有进行真机或模拟器验收，也未声明大 PDF 设备插页、旋转等待恢复、布局触控或持续书写帧率通过。设备应重点验收 365 页实际文档第 12 页后插入并在新 13 页书写；原后续页的笔迹/书签/来源定位；导出/另一编辑窗口占用时拒绝；插页等待期间旋转/后台恢复；失败后重新可操作；两种风格窄屏工具横滑时左侧按钮仍可用；侧栏开合保留交互组约定的实际缩放与阅读锚点。

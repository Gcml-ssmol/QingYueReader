# Android 导航、页面栏与书签修复交付

工作副本：`work/feedback-1.13.3/navigation/QingYuePDF`。基线：`outputs/1.13.2/QingYuePDF`（1.13.2 / versionCode 20）。本交付不改根工程、版号、签名或已发布产物。

独立审查提交：`dbab2e17d724946910f003aa2895c2203eaf8e46`。该仓库仅跟踪此次拥有的文件，以 1.13.2 对应文件为父快照，不能视为主项目完整分支。总管可按 `changed-files.json` 核验后复制交付文件，或将 `navigation.patch` 应用到准确基线；不要重复应用。补丁已在实际 1.13.2 目录用 `git apply --check` 只读检查通过。无删除文件。

## 根因和行为

- 笔记原页面栏是从屏幕左上角显示的 280 dp 全高 PopupWindow，直接覆盖标题、工具条及画布。现改为工具条下的真实左右结构；PDF 也加入同样的左侧按页缩略图。工具条始终占全宽，超宽工具通过横向滚动到达；经典 PDF 风格也把页面操作与画笔分为两行，避免固定按钮挤掉画笔区域。
- 页面栏依据实际可用宽度决定布局：小于 600 dp 使用独立模态选页列表，不压缩阅读区；600 dp 及以上默认显示 112–168 dp 栏，可收合，保留至少 480 dp 阅读宽度。PDF 选中页边框和页码同步，保留目录、书签、批注入口。笔记页预览改为只创建可见行，支持选页、当前页上移/下移及新建。
- 原书签行依赖 ListView 的 item-click，但行内删除 IconButton 可聚焦；整行点击被子控件焦点拦截。现给卡片及书签文字显式绑定目标页，编辑/删除捕获不可变书签 ID，避免显示刷新后按旧 position 误操作。删除/编辑按钮取消键盘焦点竞争，保留点击及无障碍说明。原 `pageAt` 已使用零基 PDF 页号，本轮保留该约定，没有人为加减页号。
- 原快捷收藏自动用页码命名，现新建时输入名称，现有书签可重命名并保留删除；同页可以有多个不同名称。控制字符、换行和多余空白被规整，长度最多 240 个 UTF-16 单元且不切断 emoji，空名称不保存，保存按钮去抖。重命名保持 id/page/createdAt 不变。
- 导航打开前重新载入最新元数据；异步加载带 token，旧响应不能覆盖新加载结果。继续使用原有 ReaderStore 修订保护和原子写入；发生外部修订冲突时关闭过期导航并重载，不覆盖新状态。
- PDF 缩略图队列只为可见页面请求；离屏排队项和运行项的回调可取消。收起/关闭独立 provider 后不向旧视图发布结果，已交付位图仅解除引用而不 recycle，避免显示列表使用已回收位图。PDF 页缩略图仍显示原 PDF，未扩展为批注缓存。笔记图片解码完成时通知所有仍存活的等待行，避免列表重建后新预览停留在占位色块。

## 接口和整合约定

- 新增 `ReaderSidebarLayout`、`PdfPageSidebar`、`NotePageSidebar`。画布只使用现有 `jumpTo(page)` / `page()`、`selectPage` / `movePage` / `addPage` API。
- `PdfThumbnailProvider.cancelOutside(firstPage,lastPage)` 取消区间外未发布结果，构造器及 request 回调接口保留。
- `ReaderActions.showPageNavigator()` 在窄屏直接打开页缩略图；`showNavigator()` 仍打开原文目录。
- `ReaderDocument.renameBookmark(id,label)` 保持身份、目标和时间；现有 `.reader` 版本、字段顺序、备份内容不变，继续读取 v1/v2。没有原 PDF 修改或数据迁移。
- `MainActivity` 包含 library 任务授权的窄范围接线：调用 `LibraryStore.deleteEmptyFolder(context,folderId,libraryRevision)`，不再自行修改内存后保存；成功/失败均重新读取资料库。该新接口由 `../library/QingYuePDF` 交付，本任务不拥有或复制 LibraryStore。
- 侧栏改变画布宽度后的页码、缩放和阅读锚点保持，以及初始 PDF 异步准备不能覆盖较新跳页请求，由 interaction 任务在其独占的 PdfReaderView/NoteCanvasView 中实现。最终必须一并整合；本交付没有修改这两个视图。

## 验证

Windows / JDK 17 本机执行，以下均已通过：

1. `compile-android.ps1`：96 个生产 Java 文件对真实 Android API 35 和项目依赖编译，aapt2 资源编译及链接。为核验跨组接口，仅在编译源列表中用 library 副本 LibraryStore 替代本副本基线文件；没有复制或将其纳入本交付。最终输出：`work/android-compile-20260924-183003/`，含 javac.log、sources.json、resource-check.apk；此 APK 仅为资源链接检查产物，不是可交付应用包。
2. `tests/reader-core/run.ps1`：原子存取、暂存快照、较旧异步载入/写入防覆盖；新增命名规整、空名称、emoji 长度、重命名不变 id/page/time、不按列表序号当页号、删除后 ID 不误指下一行、重开与序列化恢复、v1 旧命名书签兼容。最新输出 `work/reader-core-final/`。
3. `tests/navigation-layout/run.ps1`：240–599 dp 模态回退、600–1920 dp 栏宽上限与阅读区宽度，输出 `work/navigation-layout/`。
4. `tests/thumbnail-lifecycle/run.ps1`：缓存淘汰/关闭后已交付 bitmap 不回收、读错文件、关闭后的回调丢弃；新增以 latch 固定运行中的离屏渲染，取消后不发布旧回调且新可见页仍成功。输出 `work/thumbnail-lifecycle/`。
5. `tests/navigation-smoke/run.ps1`：真实 PDFBox 的 named/GoTo 页目标、嵌套目录、损坏标签降级、损坏 PDF、循环目录和超时边界。输出 `work/navigation-smoke/`。
6. `tests/backup-integration/run.ps1`：真实本地备份 adapter 的 pending snapshot、KEEP_BOTH、reader/ink/清空恢复及删除回滚，输出 `work/backup-integration/`。

逐文件 before/after SHA256 在 `changed-files.json`（15 项），before 均已与提供的 baseline-manifest.json 及实际 1.13.2 文件核验；新增文件 before 为 null。补丁和独立审查仓库在本任务目录。

## 设备验收边界

没有连接 Android 设备或运行 Android UI 自动化；不能把 JVM 替身测试和真实 SDK 编译写成真机交互通过。总管最终合并后的设备验收需覆盖：经典/新风格与浅深色、平板横竖屏/窄分屏、大字体、工具条左右滚动可达、PDF/笔记侧栏开合不跳页不改变缩放、快速滚动缩略图后退出、长文档连续选页、书签新建/重命名/删除/重开/备份恢复、目录与批注跳转，以及含笔迹/文本/图片的笔记页预览。初始加载过程中点击后页必须保持最后请求目标，此项尤其需验收 interaction 合并后的视图。

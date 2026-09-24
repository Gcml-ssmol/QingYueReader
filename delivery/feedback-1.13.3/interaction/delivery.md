# 1.13.3 交互修复交付

交付目录：`work/feedback-1.13.3/interaction`。源码位于本目录的 `QingYuePDF`，基线为已发布的 `outputs/1.13.2/QingYuePDF`。未修改根项目、版本、Activity、导航组目录；未打包、签名或发布 APK。

状态：代码与 13 文件清单已冻结；无删除。`changed-files.json` 的 before 来自统一 baseline-manifest.json，并再次核对发布基线文件；after 为本交付文件实际 SHA256。

## 修复范围与根因

1. **荧光笔不透明。** PDF 压感笔迹原来逐段 drawLine/stroke，半透明线段在密集连接处重复叠加，产生不均匀且近乎不透明的笔迹。PNG 路径亦逐段绘制。新增 InkPage.outline 将压感笔迹表示为方向一致的圆头与连接面，以非零填充规则一次填充整条笔划；InkStrokeRenderer 用于 PDF 画布预览、保存后回显与 PNG 导出，PDF 矢量导出复用同一几何轮廓一次 fill。新 PDF/笔记荧光笔统一使用 alpha 0x55（85/255），新 marker 使用固定宽度，保留已有笔迹的颜色、透明度和压感数据。不同笔划交叉仍按正常 alpha 规则加深。同一笔划内不再因采样点密度加深。
2. **误触缩放与笔/手掌书写时 PDF 晃动。** 原 PDF 在浏览模式下没有让 stylus 进入笔指针路由，笔与手掌可能形成缩放对；笔记双指撤销候选阶段已修改缩放；两者缺乏独立的捏合启动门槛。新增 TouchNavigation.Guard：笔落下/悬停取消触摸导航，笔拥有其 pointer ID，笔+手掌及笔抬起后的残留手掌保持整段隔离；大接触面与超过两指不能导航。PDF 浏览笔仍可独立拖动。Pinch 以两个 ID 跟踪配对，双指撤销候选不修改视图，移动/缩放需跨过门槛，激活时重新取基准，不把累计抖动一次施加。第二指抬起后不将残留指的坐标直接接入单指导航。单指平移亦有启动门槛。
3. **尺寸与加载锚点。** 工具栏在手势中收起会改变可用尺寸，fit 重新计算又改变实际比例。现将阅读滚动通知延后到手指释放；侧栏/工具栏尺寸变化保留实际像素缩放与中心锚点，并避免仅 resize 触发 PDF 换页。PDF 异步元数据/有效 View 尺寸就绪前保留最新 jumpTo 页与归一化位置，初始回调不再覆盖最新请求。程序化 jumpTo 即时定位，避免相邻页动画过程中侧栏收合取消动画而丢失新页请求；手指滑动保留翻页动画。PDF 页实际比例回填时保持当前页的像素宽度/中心。缩放上下限、文档边缘约束仍生效，因此边缘附近允许必要的边界归位。
4. **笔记左右/上下拖动。** 原翻页方向与自然手势相反，且只容许几乎未平移内容的拖动翻页。现在按 setHorizontalPages 选择主轴：向左/上到下一页，向右/下到上一页；先消耗放大内容平移，再累计边缘未消耗距离，斜向移动或反向移动不会误翻页。末页拖动新增要求手形工具、手指、约 100% fit 比例、至少 250ms 且边缘位移达到 180dp；书写工具、笔输入或放大内容拖动不会误增页。普通已有页边缘翻页阈值为 90dp。

## 参考 APK 证据及使用边界

仅用 aapt2 读取包身份与资源，未安装、启动或实际体验未知包，未将其资源或反编译实现复制到晴阅。

- 新文件 `C:/Users/xsh12/xwechat_files/wxid_zotrj64rl8dl22_6895/msg/file/2026-09/黄色笔记.1` 实际为 **云记 / Jnotes**，`com.jideos.jnotes`，versionName **3.2.6.4**，versionCode **534**，minSdk 24 / targetSdk 35。原始证据为本目录 `reference/jnotes-badging.txt`、`reference/jnotes-resources.txt`。
- Jnotes 资源出现“透明度”（resources 第 241563 行）、“仅擦除荧光笔”（242522）、支持触控笔书写同时手指拖动/翻页的英文说明（243364）、“纸张自由移动”（249616）、“滑动新增”（249861）、“双指点击”（250707）。`tip_content_free_move_enable`（约 250899）明确提到自由移动时只有 100% 缩放比可滑动新增，随后 `tip_drag_left_add_page` / `tip_drag_up_add_page` 分别提示左拉/上拉添加页面。这些资源支持输入分工和边缘新增的设计方向，不能证明实际事件处理、透明度数值或捏合阈值。
- 已有参考 `work/reference-app/reference.apk` 为享做笔记 **5.5.13**，`com.newskyer.draw`，versionCode **46956**。其现有资源报告 `work/reference-app/resources.txt` 包含 `palm_rejection` / “防误触”（19531–19533）、“调整线条透明度”（16816）及“荧光笔”（19190）；身份分析沿用 `QingYuePDF/delivery/reference-app-5.5.13.md`。
- 本轮 280ms 笔后隔离、8dp 平移/捏合 slop、6% 初始 span 变化、24dp 最小双指距离等为晴阅自己的保守初始参数，并非从参考 APK 提取的实现常量。`pen_touch_light_pinch` 是笔按钮相关资源，未当作双指缩放证据。

## 文件与接口

权威逐文件 SHA256 清单：`changed-files.json`，13 个文件：

- 生产代码：InkInput.java、InkPage.java、InkStrokeRenderer.java（新）、TouchNavigation.java（新）、PdfReaderView.java、NoteCanvasView.java、PdfAnnotationExporter.java。
- 测试：tests/interaction/InteractionTest.java（新）、tests/interaction/run.ps1（新），及 tests/pdf-search-smoke 的 PdfExportSmoke.java、run.ps1、android/graphics/Canvas.java、android/graphics/Path.java。

现有 `PdfReaderView.jumpTo(int)`、`jumpTo(int,float)`、`page()` 与 `NoteCanvasView.setHorizontalPages(boolean)` 保持兼容，无 Activity 接线变更。InkPage 的序列化版本和字段保持兼容；新增 outline 接口自身无外部几何类依赖，因此原显式编译 InkPage 的 pure-java/note-store/reader-core/backup-integration 等脚本不需要追加生产源。pdf-search-smoke 显式追加 InkStrokeRenderer，并补足对应 Android Path/Canvas 测试替身方法。

已通知导航组 `/root/prepare_release_metadata`：可直接使用已有跳页/方向 API。未修改它独占的 PdfThumbnailProvider、NotePagePreviewView。笔记预览、BasicNoteExport、笔记缩略图本来以完整 Path 绘制并使用存储颜色，此次统一存储 marker alpha 后保持这些通道一致；未为此重复修改其代码。PDF 缩略图沿用**仅原始 PDF 页，不显示批注**的既有行为，未新增可能缺少笔迹版本缓存失效的叠加。不能将其称为“批注缩略图已验证”。

## 验证命令与结果

以下命令从本交付目录运行；全部生成物留在本目录 work 内。

```powershell
& ./compile.ps1
& ./QingYuePDF/tests/interaction/run.ps1 -BuildDirectory ./work/interaction-final
& ./QingYuePDF/tests/pdf-search-smoke/run.ps1 -BuildDirectory ./work/pdf-tests
$suites = Get-ChildItem -LiteralPath ./QingYuePDF/tests -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'run.ps1') } |
    ForEach-Object Name
& ./QingYuePDF/tests/run-regression.ps1 -BuildDirectory ./work/regression -AdditionalSuiteAllowList $suites
```

- 最终真实 Android API 35 编译：**95 个生产 Java 源通过**，使用真实 PDFBox/OCR 依赖。日志 `work/compile-final.log`；此步骤仅 javac 编译，不是 APK 构建。
- 新增交互回归最终通过，日志 `work/interaction-final.log`。覆盖“手掌先落下→笔到来→混合移动→笔抬起→手掌残留→新手指手势”的状态机、悬停隔离、笔 ID 所有权、双指撤销候选抖动、平行拖动不缩放、真正捏合、指针换位/替换、窄 span、主轴边缘翻页与误增页拒绝、resize 中心数学与往返无漂移。
- 真实几何透明度回归：201 个密集压感点生成 InkPage.outline，经 AWT 非零规则实际栅格化，连接区 alpha 恰为 85，轮廓外 alpha 为 0；PDF/笔记序列化后 alpha 保持。该测试不是 Android GPU 像素验证。
- 真实 PDFBox 导出回归通过：同一压感荧光笔只有一个填充操作且无逐段 stroke，nonstroking alpha=85/255；Canvas 测试替身验证共享渲染入口一次 drawPath、零逐段 drawLine。原有旋转/crop、矢量层序、源文件不变、文本保留、取消/范围导出与 PNG 大小/OOM 边界继续通过。日志 `work/pdf-tests.log`。
- 全部既有及新增脚本回归：**PASS=35 / FAIL=0 / SKIP=0**；日志 `work/regression.log`，结构化结果在 `work/regression/20260924-183644826-100dccbd`。涵盖 11 个既有纯 Java 测试与全部脚本套件，包括 global-search、note-store、reader-core、backup-integration、basic-note-export、thumbnail-lifecycle 等。全回归后补齐 PDF 异步页比例校准的锚点保持、清理 NoteCanvasView 无用字段、补齐笔接管的手势起点和修正测试时间顺序；这些源码再次通过上述真实 SDK 编译与交互专项测试。导航组交叉审查后另移除程序化相邻页 jumpTo 的动画分支，修复缩略图点击后立即收合侧栏导致请求丢失；该最终单行调整再次通过真实 SDK 编译。

## 可复核补丁与集成

独立审查 Git 位于 `review/.git`，仅记录本次相关文件的发布基线和修改，未使用或修改根项目 Git。

- 基线提交：`140fdd4`。
- 修复提交：`add880bbc4bced2d8a30d0b0c9012f28191ee403`。
- 导出补丁：`0001-Fix-marker-alpha-pen-palm-ownership-and-note-edge-na.patch`。
- `git diff --check` 通过，审查仓库工作树干净。

整合时按 changed-files.json 校验 before 后逐文件覆盖/新增，或应用补丁，并重新校验 after。不删除任何文件。

## 尚未执行的验证

未在真机或模拟器安装运行本轮代码；未实际测试笔/手掌设备、Android GPU 透明混合、PDF 连续加载动画、侧栏开合或 SAF 导出。接触面积/TOOL_TYPE/hover 的实际报告因硬件而异，参数仍需目标设备验证。最新 jumpTo、异步页面比例与 View resize 接线经源码核对和真实 SDK 编译；专项测试覆盖其共享锚点数学，不声称做过 Android View 事件回放。建议主窗口正式整合后在用户复现设备重点验证笔+手掌混合、二指轻点撤销、真正捏合、侧栏开合、横竖向边缘翻页与透明荧光笔 PDF/PNG 导出。

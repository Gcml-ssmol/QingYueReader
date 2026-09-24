# 1.13.4 绘制与顺滑性优化交付

基线：`outputs/1.13.3/QingYuePDF`。修改仅在本目录 `QingYuePDF` 副本，未改版本、Activity、插页模型、根项目或历史产物，未构建/签名/运行 APK。9 文件清单见 `changed-files.json`，before 已与发布文件及统一 baseline-manifest.json 核对，无删除。

## 热点与改动

- **每帧重建笔迹路径。** InkStrokeRenderer 新增每个 View 独立的有界几何缓存（最多 256 项、60000 个采样点）；每帧已经使用的项固定保留，超容量顺序扫描不会将整页缓存反复驱逐。超过预算的单条笔迹仍正常绘制但不保留。以不可变 Stroke/Item 对象身份作键，编辑生成新对象、撤销恢复旧对象，颜色/宽度每次从当前对象读取。PDF 页面边界表按笔迹快照建立；整笔橡皮原地删项导致数量改变也会重建边界。放大时仅给视口相交笔迹构建/绘制路径，仍按原笔迹次序混合。
- **笔记浮点精度。** 笔记缓存每条笔迹的局部坐标，先以 double 计算局部原点到视口中心的差，再作屏幕平移。跨度或平移过大（屏幕尺度超过 1048576）回退原有逐点屏幕坐标路径，避免无限画布远离原点时细线丢失。实时草稿不进入保留缓存；单点最小显示半径保持原样。
- **PDF 瓦片每帧分配/排序。** 绘制列表只在瓦片缓存变化后重建和排序，复用 RectF，跳过完全在视口外的瓦片。保持低分辨率先绘、高分辨率覆盖次序，Bitmap 驱逐仍只释放引用，不 recycle 已交给显示列表的位图。
- **离屏预取抢占高清首批次。** PdfRenderPlan 将真正相交的瓦片放在预取边缘之前，同组按视口中心距离排队；总覆盖集合与像素预算不变。取消高清请求时移除未开始的队列任务，任务在打开 PDF 前再次检查代次；过期完成回调不得移除新代次的 pending 标记。
- **插页协作。** 提供 `NoteCanvasView.insertPage(int index)`，复用完成手势/视口同步/清选择/重新初始化/保存/host.pageAdded 流程。调用 insertion 任务的 `NoteDocument.insertPage(int)`，整合时必须同时接收该模型。两 View 的 finishGesture/持久化入口补 disposed 防线；dispose 返回前完成原有同步 stage/save。编辑租约只由 Activity 持有，不重复获取。PDF 插页需 dispose View/provider→同一 Activity worker 排空既有读写→释放租约→插页事务→重开跳页。独立高清 worker 只读 PDF，不写 sidecar。

未改 1.13.3 的 TouchNavigation、笔/手掌隔离、双指门槛、荧光笔单次填充或序列化。页面遍历仍有 O(笔划数) 边界检查，首次显示仍需建立几何；没有重写阅读器、改变页数据或扩大到整页栅格缓存。PdfThumbnailProvider 既有 cancelOutside 与安全 Bitmap 生命周期继续沿用，未另改其资源关闭机制。

## 参考 APK 静态证据

用 aapt2 dump badging/resources 与 SHA256 读取两份指定文件，未安装或启动，未复制其实现和资产。完整报告在 `reference/apk1-badging.txt`、`apk1-resources.txt`、`apk2-badging.txt`、`apk2-resources.txt`。

| 文件 | 核实身份 | SHA256 |
|---|---|---|
| base.apk(1).1 | 享做笔记 / touchnotes，com.newskyer.draw，5.5.13 / 46956，min23 target29 | f8eef5324f2f09be587f3575b7ecd7ef1a018409be42c08eb72fc0a8074f6030 |
| base.apk(2).1 | 云记 / Jnotes，com.jideos.jnotes，3.2.6.4 / 534，min24 target35 | 4f43408309a8f8e5f32181f1de947c440c55c5cb6c60e2fd6c35db25788ba2d3 |

两文件分别与上轮 `work/reference-app/reference.apk`、`黄色笔记.1` **逐字节哈希一致**，并非新的参考版本。

享做笔记资源包含竖向/横向连续翻页（17629/17632）、`switch_page_on_edge_title` 的“滑动到边缘翻页”（20470–20472），以及纸张自由停留时单页滑动需最小页大小的提示（20679–20681）。云记资源包含“翻页方向”（246167）、“纸张自由移动”（249616）、“滑动新增”（249861）及自由移动时仅 100% 可滑动新增的说明（250899）。这些能证明包内存在相应设置/交互提示，不能证明实际运行行为、帧率、缓存算法或渲染线程策略。本次优化依据晴阅生产源码与生产入口计数，未声称体验参考应用。

## 可复核性能计数与测试

计数调用生产 InkStrokeRenderer / PdfRenderPlan，Android Path 替身仅记录构造、几何命令和绘制调用；不是 Android GPU、设备耗时或 FPS 测试。

| 场景 | 1.13.3 路径 | 优化后 |
|---|---:|---:|
| 160 条×201点压感笔迹×100帧：Path 构建 | 16000 | 160 |
| 同场景：Path 几何命令 | 35296000 | 352960 |
| 400 条笔迹（超过256项）×60帧：Path 构建 | 24000 | 8896 |
| 首批4块高清瓦片中与视口相交数 | 0 | 4 |

另覆盖点数预算超限、超大笔迹不驱逐整个缓存、新页准入、颜色/宽度/编辑/撤销/删除、整笔橡皮原地删项、压力半径边缘相交、缓存几何与导出入口一致、1e12 世界坐标下 0.5/1/3/20 倍缩放精度。

从本目录运行：

```powershell
& ./compile.ps1
& ./QingYuePDF/tests/render-performance/run.ps1 -BuildDirectory ./work/render-performance
& ./QingYuePDF/tests/interaction/run.ps1 -BuildDirectory ./work/interaction
& ./QingYuePDF/tests/pdf-search-smoke/run.ps1 -BuildDirectory ./work/pdf-search
$suites = Get-ChildItem ./QingYuePDF/tests -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'run.ps1') } | ForEach-Object Name
& ./QingYuePDF/tests/run-regression.ps1 -BuildDirectory ./work/regression -AdditionalSuiteAllowList $suites
```

- **98 个生产 Java 源真实 Android API35 / PDFBox / OCR 编译通过**。compile.ps1 只读引入 insertion 所拥有的 NoteDocument 模型，以验证新插页接口，其余为本副本源码；未复制模型到本交付。日志 `work/compile-final.log`。
- 专项、原交互/透明度以及真实 PDFBox 导出全部通过，日志分别为 `work/render-performance.log`、`work/interaction.log`、`work/pdf-search.log`。
- **完整回归 PASS=37 / FAIL=0 / SKIP=0**，含缩略图生命周期。日志 `work/regression.log`，结构化结果 `work/regression/20260924-203737194-5336c2b4`。全回归使用本副本基线模型，插页事务测试归 insertion 任务；不把这 37 组声称为已整合插页验证。
- 未进行真机笔/手掌、GPU像素/抗锯齿、持续滚动帧耗时/峰值 native 内存或真实侧栏/插页 UI 测试。设备性能提升幅度仍需真机测量；不保证旧页第一次几何建立或超过缓存预算的全部内容均无停顿。

## 集成工件

独立审查 Git：`review/.git`（仅本次文件的基线与变更）；基线提交 `4029cdf`，最终提交 **ee91b977a159d259cb805ace9654be356df95d32**。补丁 `0001-Cache-stroke-geometry-and-prioritize-visible-PDF-det.patch`；`git diff --check` 通过。按 changed-files.json 校验 before、复制/新增 9 文件并验证 after。生产改动仅 InkStrokeRenderer、NoteCanvasView、PdfReaderView、PdfRenderPlan；另有 5 个 render-performance 测试文件。

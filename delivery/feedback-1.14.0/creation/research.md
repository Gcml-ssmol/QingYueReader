# 云记创建草稿流程：静态证据与晴阅实现边界

研究对象：`C:/Users/xsh12/xwechat_files/wxid_zotrj64rl8dl22_6895/msg/file/2026-09/base.apk(2).1`；只读使用 Android SDK `aapt2 dump xmltree --file` 与已存的 `work/feedback-1.13.4/interaction/reference/apk2-resources.txt`。下述是 APK 资源能证实的界面元素，**并非云记运行截图或完整交互验证**。不复制其代码、图案或资源。

云记同时有普通笔记的 `fragment_note_detail.xml` 和无限草稿的 `fragment_nb_note_detail.xml` 两套布局。后者没有前者的纸张模板/页列表 include；下表 `pop_create_note_layout.xml` 的封面、纸色和模板只能证明通用创建界面有这些元素，不能证明无限草稿创建也使用它们。晴阅本轮因此只给无限草稿提供现有可保存的底纹，不添加未经验证的封面和纸色字段。

## 明确证据

| 行为/元素 | 资源证据 | 可确认范围 |
|---|---|---|
| 手机创建入口含“新建笔记”“新建文件夹”“导入 PDF” | `res/layout/phone_new_create_pop.xml`，文字资源 0x7f110854、0x7f110846、0x7f110632 | 同一创建弹出层有这些入口；不能由 XML 确认点击后的导航顺序 |
| 创建页有取消/创建及封面、纸张分区 | `res/layout/pop_create_note_layout.xml` 第32、45、91、122行及后续多个 RecyclerView | 封面和纸张可选择；创建页是包含分类与网格的较大面板 |
| 创建页有封面分类和纸张颜色 | 同布局第159–313行：色彩/自然/艺术、白色/米色/深灰色/春节主题；第337行“选择纸张模板”及第361行网格 | 预置封面分类、纸色与模板选择明确存在；具体可用模板数量与付费限制未确认 |
| 纸张方向选择 | `res/layout/popup_paper_orientation_select.xml`；资源 `string/transverse_paper`、`string/page_direction` | 有方向选择弹层；不推断其对已有页和 PDF 的确切副作用 |
| 默认创建偏好 | `res/layout/setting_create_note_default.xml`；`string/default_cover_and_paper`、`default_cover`、`default_paper`、`default_writing_setting` | 设置中有默认封面/纸张及笔刷、压感、颜色等项目；不能确认是否每项都在新建时预设 |
| 模板/纸色相关单独界面 | `res/layout/include_set_paper_template.xml`、`popup_paper_bg_color_select.xml`、`pad_dialog_change_paper_bg.xml`、`phone_dialog_change_paper_bg.xml` | 具有独立选择 UI；不能证明自定义图片导入流程 |
| 名称与文件夹 | `string/default_note_name` 为“文档”，`string/untitled_draft` 为“未命名草稿”；`phone_new_create_pop.xml` 有“新建文件夹” | 默认名称与文件夹入口存在；创建面板 XML 无 EditText，不能声称创建时直接输入名称或选择目标文件夹 |

## 晴阅现状与缺口（只读基线 `outputs/1.13.5/QingYuePDF`）

`MainActivity.java:398` 调 `NoteDialogs.create`，回调接收 `name/template/paper`，`NoteStore.create` 写笔记，若资料库当前选中文件夹则继承该文件夹。`NoteDialogs.java:36–125` 已有名称、全空/横线/网格、A3/A4/A5/B4/B5/Letter/无限大小和预览，默认“未命名笔记”+全空+A4，不能在创建时明确查看/更换目标文件夹。创建入口在经典/奶油界面均有。`NoteDocument` 当前固定纸张的宽高由 size 计算，创建 UI 不能单靠改尺寸文案支持横向、纸张颜色或封面。

建议先实现晴阅自己的精简创建工作台：名称、当前目标文件夹、底纹和尺寸一屏可见；预览随选择更新；本机记住用户上次底纹和尺寸偏好，并提供恢复默认。目标文件夹用现有 `LibraryFolder` 树选择，不改现有默认继承逻辑。封面、纸色、方向、独立默认设置需要核心数据 API/存储字段后再挂接真实操作；在 API 前不显示无法生效的按钮。保留晴阅奶油橙、左侧资料树和离线本地特点。

## 精确所有权与接口建议

- 本任务独占 `NoteDialogs.java` 的创建面板、可新增 `NoteCreationPanel.java` 及其独立辅助类，并改 `MainActivity.java` 的创建挂接；不编辑 `NoteDocument.java`、`NoteCanvasView.java`、`NoteActivity.java`、`AppColors.java`。
- 核心数据 owner 定义 `CreateNoteOptions` 或等价数据结构：`name`、`template`、`paperSize`、`orientation`、`paperColor`、`coverId`、`folderId`；约束旧 v1–v3 笔记读取与导出兼容。未落库字段不加入 UI。
- 资料库 owner 确认 `LibraryFolder` 选择/写入接口及封面在列表中的持久化方式；本任务再把 UI 结果交给 MainActivity 创建逻辑。
- 开发在 `work/feedback-1.14.0/creation/QingYuePDF` 隔离副本进行；等待核心 API 明确后实现与真实 SDK 编译。基线不可改。

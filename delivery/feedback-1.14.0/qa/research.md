# 云记草稿编辑证据、晴阅 1.13.5 基线差距及 1.14.0 实施状态

日期：2026-09-24。基线：outputs/1.13.5/QingYuePDF。参考文件 base.apk(2).1，com.jideos.jnotes，3.2.6.4 / 534，SHA256 4f43408309a8f8e5f32181f1de947c440c55c5cb6c60e2fd6c35db25788ba2d3。本报告不等于“已复制所有功能”。

## 证据强度和约束

本轮只读静态研究，未安装、运行参考包或复制其代码/图片。资源文本 R 仅证明文案存在；布局 X 证明控件/视图引用存在；DEX D 只证明可读符号，不能单凭名称推断事件处理。可读的 13 个根 DEX 没有定义 com.jideos 的业务类，包中有 assets/baiduprotect*.i.dex、*.jar 与 libbaiduprotect；35 个该类资产未呈标准 DEX 头，因此没有还原其业务方法。可读路由中有普通平板/手机 NoteDetailActivity，但没有获得无限草稿创建分支的可执行调用链。不能声称静态研究确认所有工具在无限草稿均可用。

adb devices -l 为空；已知 SDK 仅 platforms/build-tools/platform-tools，没有 emulator/system-images，未发现 AVD。本轮无法动态检查其默认状态、操作顺序、付费开关、手势手感、动画时长或真正布局。

## 无限草稿与普通笔记的可证实区别

- R：add_default_nb_note_name = 未命名草稿（资源表239334），nb_note = 无限草稿（245191），new_nb_note = 新建无限草稿（245257），vip_function_nb_detail（252305）描述可无限扩大的画布。
- X：fragment_nb_note_detail.xml 使用 NbControlPanel；nb_draw_panel.xml 包含 NbContentLayer、NbTempLayer。该布局有 location_last / location_refresh / location_next 浮动定位组、scale_lock_layout，以及录音简单条/播放条 include。录音播放条和 scale_lock 默认 GONE。不能据此断言录音免费可用。
- X：普通 fragment_note_detail.xml 明确具有 page_recycler_view、翻页容器、纸张模板 include；无限分支没有这些控件引用。由此只能证明布局区别，不能断言无限草稿绝对不支持背景或其他工具。
- X：pop_create_note_layout 有封面/纸张挑选。没有可读业务调用链将其绑定到 new_nb_note，因此“创建无限草稿只需名字”“草稿必有封面纸张”的说法都尚未证实。创建任务需保留这一不确定性。
- 编辑工具 edit_tool_bar_v2 布局含 undo/redo、可滚动工具容器、右侧属性面板，以及多种笔、橡皮、文字、图片、图形、激光、手写转换、胶带控件；它是通用布局，不能独立证明每种工具都出现在 NbControlPanel。

用户本轮已明确晴阅新建草稿默认无限画布；该产品决定不依赖推测云记运行行为。晴阅应保留已有分页笔记的独立入口和左侧页栏，不给单张无限画布强加“自动新增下一页”。

## 功能矩阵

状态为晴阅 1.13.5 源码核对；“缺少”指该编辑界面未提供，不代表整个应用毫无相关模块。

| 功能 | 云记可证实证据 | 无限草稿归属 | 晴阅 1.13.5 | 本轮适合的动作/验证 |
| --- | --- | --- | --- | --- |
| 无限画布/定位 | NbControlPanel、NbContentLayer/TempLayer；location_*；detail_menu_ten_scale | 画布/定位直接 X；10倍仅通用 R | 有无限双轴坐标，50–300%，无原点按钮 | 增10倍、原点/适配；远坐标回归 |
| 撤销/重做 | edit_tool_bar_v2 btn_undo/redo | 通用工具 X | 有，页切换清历史 | 保留；多对象每次一个undo |
| 工具栏滚动/属性子面板 | EditToolRecyclerView + PropertyFrameLayout | 通用 X | 横向工具条；长按调色粗细 | 增明确长按提示与紧凑面板 |
| 钢笔/圆珠笔/铅笔/荧光笔 | 各PropertyView、ink_pen_pop、pencil_pop_pad | 通用 X；运行未证 | 有对应工具，笔记为宽度/颜色路径；铅笔透明度 | 不把相同路径标为真实压感/倾斜笔刷 |
| 笔压/倾斜/笔刷样式 | vip_function_pencil_detail；压力滑杆布局 | 通用 R/X | 笔记不保存逐点压力/倾斜 | 格式/渲染能力缺口；不能虚设开关 |
| 笔盒预设 | include_pen_box、add_to_pen_box；vip_pencil_case_tips | 通用 X/R | 单工具参数记忆，无多预设 | 本地有限数量预设可新增 |
| 局部/整笔橡皮 | eraser_pen_pop：像素擦除/整笔擦除 | 通用 X | 局部路径切分 | 加整笔模式；数据回归 |
| 只擦荧光笔/胶带 | only_markpen_switch/only_tape_switch | 通用 X | 无过滤、无胶带 | 缺口单列，不虚构胶带 |
| 压感/长按橡皮 | setting_quick_eraser、pressure_sensitivity | 通用 R/X | 笔橡皮tool支持；无压力阈值菜单 | 手写硬件和状态机需专项 |
| 自由/矩形套索及筛选 | lasso_type_pop/phone_lasso_type_pop；lasso_style；图片/文字/图形 | 通用 X/R | 只能点选单个文字/图片 | 新增多对象自由/矩形与类型筛选 |
| 套索变换/复制剪切粘贴/颜色 | lasso_hint_text 244072；pop_lasso；copy_and_paste_tip | 通用 X/R | 文字图片可拖动缩放；无多选复制 | 实现v3可表达的操作，旋转另列 |
| 套索旋转/链接/转文本 | lasso_hint_text | 通用 R；付费/云依赖不确定 | 来源链接仅固定PDF回链，无通用旋转/OCR对象转换 | 不伪造按钮 |
| 文本插入编辑 | AddTextPropertyView、popup_drag_text_menu | 通用 X | 文字框、字号颜色、拖动缩放 | 保留，支持选区批量操作 |
| 手写即时转文字/合并文本 | handwriting_title/merge/split；HandWritePenPropertyView | 通用 R/X、会员提示 | 仅PDF/OCR体系，笔记无即时转换 | 单列缺口与依赖 |
| 图片导入/移动缩放/裁剪 | PictureView、image_editor/image_crop布局 | 通用 X | 导入/EXIF修正/移动缩放，无裁剪UI | 保留；裁剪旋转需另实现 |
| 标准图形 | GraphView、standard_graph_pop | 通用 X | 线/圆/矩形/箭头 | 保留明确工具，不冒充全形状库 |
| 长按自动图形/一笔成型 | setting_graph_detect_detail 249640、graph_one_hint_text | 通用 R | 无检测器 | 算法与识别容错缺口 |
| 激光笔 | LaserPointerPenPropertyView；new_function_laser文案 | 通用 X/R | 无临时消失轨迹 | 单列，非永久marker |
| 胶带/贴纸/扫描 | AdhesiveTapePenPropertyView、StickerRecentlyView、scanner提示 | 通用 X/R | 无胶带/贴纸库/扫描UI | 不复制资产，分别实现才可宣称 |
| 页追加/前后插/复制/删/排序 | detail_outline_pop_menu；insert_new_page/copy/delete_page；长按缩略图调序 | 普通页管理 X；Nb未证 | 追加/前后插/上移下移，缺复制删清空 | 只在有限页管理中增加，至少保一页 |
| 页面清空 | clear_page文案且“无法还原” | 通用 R | 无操作入口 | 晴阅可设计可撤销清空，避免照搬破坏性行为 |
| 背景模板/封面 | include_set_paper_template、popupwin_change_template、创建cover/paper | 普通分支直接 X；Nb未知 | 新建 blank/ruled/grid、标准尺寸/无限；不可后改模板 | 创建owner设计；逐页模板需数据升级 |
| 连续/横向/自由移动 | include_set_page_direction；setting_edge_turning；vip_power_vertical_page_continuous | 普通页 X/R | 已有连续纵向/横向、放大拖动 | 保留1.13.5；不混入Nb分页 |
| 只读/阅读模式 | vip_function_readMode_detail：眼睛图标、激光/搜索/文本操作 | 通用 R | hand工具，但没有明确阅读状态开关 | 显式只读，手写笔也不落笔 |
| 多页并列/分屏/标签 | 普通tab_layout；new_function双开提示；split_fragment | 通用/普通 X/R | 单编辑画布，无双开/并列 | 布局/生命周期专项，不能假装已支持 |
| 侧栏/鸟瞰/缩略图 | view_note_side_bar、detail_menu_preview_setting | 通用 X；Nb归属未知 | 有左栏选页、移动顺序 | 保留侧栏；无限画布应显示定位而非假页数 |
| 笔记内搜索/书签/大纲 | layout_note_detail_search、view_sidebar_search；vip_function_search_detail | 通用 X/R | 全库文字搜索、PDF书签；笔记编辑内无搜索/书签/大纲 | 清晰缺口，不把PDF能力当笔记已有 |
| 导出PNG/PDF/原生/范围 | popupwin_detail_export_note/exportpdf；可编辑/扁平PDF资源 | 通用 X/R；Nb具体范围未知 | 全页PDF、当前页PNG，qyb备份 | 保留本地真实导出；超大无限范围需边界 |
| 录音/同步回放 | Nb布局直接include录音条；vip_function_record_detail | Nb直接 X；实际状态/权限未知 | 无录音模型 | 需权限/音轨/时间戳，不做空壳按钮 |
| 录音转写 | 10秒阈值/单独付费文案；登录提示 | 通用 R | 无 | 云端/收费依赖单列 |
| 笔记关联/内部超链接 | layout_relate_notes、link_select_note_page；vip_link_tip | 通用 X/R | 来源文字可返回PDF指定页/位置 | 保留晴阅来源特色；通用双链待做 |
| 图层显隐锁定合并 | main_layer_*、vip_new_layer_content | 通用 R；Nb实际未证 | 单列表绘制，无独立图层 | 本轮仅对象前后层级；不称图层功能 |
| 加密/云同步/会员资源/AI | vip_note_encryption/cloud；market与AI布局 | 通用 R/X | 本地离线，无账号或云AI | 单列依赖，不擅接服务 |

## 付费/联网证据单列

出现会员解锁文案的功能有录音、上下连续翻页、手写转文本、可编辑PDF导出、超链接、合并文档、加密等。vip_power列表还列出很多基础工具，因此“在会员表出现”不等于每一项完全收费。模板有“需登录后使用”；纸张类目错误提示要求网络；录音转写明确登录、单独付费。晴阅本轮沿用本地离线原则，不复制账号/会员/广告/付费条件。

## 本轮编辑UI实施边界

用户决定默认无限草稿。core任务实现v3可表示的套索批量操作、整笔橡皮、页复制删清空、10倍与原点；本任务接编辑UI、笔盒预设、操作面板/只读/缩放。创建任务提供NoteMotion用于短淡入微滑与Activity过渡；不对持续书写画布加动画。现有PDF来源回链、奶油橙/经典主题、离线保存与左侧有限页栏保留。

研究原件：reference/feature-strings.tsv、layout-controls.txt、49份*.xmltree.txt、dex-summary.json、zip-entries.txt。可复跑 inspect_apk.py、inspect_layouts.py、inspect_dex.py。根DEX没有读到业务类定义的事实限制了调用链判断；不宣称功能全覆盖或动态体验完成。

## 1.14.0 本轮实施状态（区别于上表的 1.13.5 基线差距）

本轮已接入并验证的编辑能力：自由/矩形套索和笔迹/图形/文字/图片筛选；选区拖动、角点缩放与百分比缩放、复制/剪切/粘贴/删除/改色/置顶置底；局部/整笔橡皮；最多8支本地笔盒预设；有限页复制/清空/删除/调序；明确只读浏览；无限画布最高1000%、原点定位；工具面板/菜单短过渡。默认无限草稿的创建流程与动画helper由creation任务交付，数据和触控由editor任务交付，本QA/UI任务只交付自己的接线和测试。

旧版多页无限笔记的每张画布仍彼此独立：左侧保留“画布 i/N”与切换/管理入口，导出PDF标明全部独立画布。新的单张无限草稿显示定位入口，不假装连续页。只读标志由Canvas执行，禁写也覆盖横向末页自动加页、硬件橡皮和公共写API。清空可撤销；删除画布/页面不能撤销，界面会展示实际目标与对象数量确认并至少保留一页。

仍未实现：真实逐点压力/倾斜笔刷、独立图层与图层显隐锁定合并、选区旋转、胶带/贴纸/扫描、长按智能图形/完整标准形状库、图片裁剪旋转UI、笔记内搜索/书签/大纲、临时激光轨迹、手写即时转文字/笔记OCR入口、多窗口分屏或多页并排、音字同步/录音/转写、通用内部超链接和双链、导出密码/范围/原生可编辑PDF、云同步/账号/会员或AI服务。现有PDF来源回链、全库文字搜索、PDF OCR与书签不是这些笔记编辑功能的替代实现。

最终专项结果：207条断言，包含生产Canvas触控与绘制入口、菜单回调/禁用状态、生产笔盒序列化与历史画布展示策略。新增覆盖4类对象矩形/自由套索、拖移/角点缩放/单次undo、颜色保留alpha、剪贴板新ID、轮廓几何反例、整笔/局部擦除、页复制删清空、1000%保存恢复、只读末页長拉控制组、遗留3画布逐个访问。保留1.13.5原有134条用例。

真实Android API35与真实PDFBox/OCR依赖编译111个生产Java源通过。合并测试输入只存在qa/work/combined，不纳入交付。测试日志qa/work/view-test-final.log、qa/work/compile/javac.log和result.txt。

桌面替身只替代Android依赖并记录绘制命令，NoteActivity/真实WindowManager的布局和动画未在设备运行。菜单字体、图片异步加载、系统大字/窄分屏、手写笔压力和实际流畅度仍需真机验收。本报告不声明已全量复制云记。


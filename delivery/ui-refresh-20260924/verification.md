# UI 更新验证记录

验证环境：Windows、JDK 17、Android SDK 35、本地真实 Java 依赖、桌面 Edge/Chromium。未连接 Android 或 Apple 设备，未运行 Xcode。

| 检查 | 结果与范围 |
| --- | --- |
| Android 生产源码 | 93 个 Java 文件，`javac --release 8` 对 Android 35 及项目依赖编译通过。 |
| Android 资源 | aapt2 compile/link 通过。资源链接文件不是可安装的完整应用。 |
| Android 既有回归 | 33 组通过，0 失败，0 跳过；涵盖文档、笔记、阅读、导出、备份、导航、搜索及 OCR 数据逻辑。 |
| Android 外观新增回归 | 默认/升级选择、经典深浅色回归、系统模式、revision、纸张与导出颜色隔离通过。 |
| 网页偏好单测 | 3 项通过：缺失/损坏偏好回退、经典/深浅/系统模式、浏览器拒绝 localStorage getter 时安全启动。 |
| 网页手机 smoke | 通过：嵌套目录、笔记/中文文本持久化、新增页、中文 PDF 导出、备份恢复、离线重载。 |
| 网页外观浏览器回归 | 全部通过：默认新风格、经典恢复与持久化、系统主题变化、主题选择不改文档、搜索与类型筛选、底部导航、继续阅读、320/390/834/1194 宽度布局、阅读工具/侧栏、新资源离线重载；无 pageerror。 |
| iOS 资源构建 | 实际运行 esbuild 完成；232 项本地资源，与 Xcode public 内逐文件 SHA-256 一致。 |
| iOS 项目与导出单测 | project 3/3，export 8/8，通过。 |
| iOS 外观浏览器回归 | 新旧风格持久化、浅深/跟随系统、内建离线提示、不注册网页 Service Worker、手机/平板无横向溢出，通过。 |
| iOS 原生导出界面 | PDF 保存、复用已保存文件、分享取消、设置/迁移说明、qyweb 备份入口通过；原生插件由桌面替身模拟。 |

## 实际入口与复现

Android：

```powershell
./work/ui-refresh-20260924/compile-android.ps1
./QingYuePDF/tests/appearance/run.ps1 -OutputDirectory ./work/ui-refresh-20260924/appearance-tests-final
```

原有回归命令及全日志在 `work/ui-refresh-20260924/regression.log`，最终编译日志为 `android-final-compile.log`。构建结果输出在任务专属 work 目录，没有覆盖历史 APK。

网页版本地静态服务根目录为 `QingYueWeb/dist`；修改后已执行 `node QingYueWeb/prepare-offline.mjs` 更新缓存清单。运行 `tests/smoke.cjs`、`tests/appearance.cjs` 时，将 `QY_TEST_URL` 指向该服务，并将 `QY_PLAYWRIGHT_MODULE` 指向本机 Playwright。新增样本文档只写入隔离浏览器测试上下文，不会自动出现在用户资料库。

iOS 已实际运行：

```powershell
node QingYueIOS/scripts/prepare-web.mjs
node work/ui-refresh-20260924/sync-ios-source.mjs --public
node QingYueIOS/tests/project.test.mjs
node QingYueIOS/tests/export-file.test.mjs
node QingYueIOS/tests/appearance.cjs
node QingYueIOS/tests/native-ui.cjs
```

后两项指向 `QingYueIOS/www` 的本地静态服务。Xcode、iOS WebKit、Apple Pencil、真实系统文件分享与 Android 设备布局仍由总管进行设备验收。

## 审查修正

- 修复进入文件夹后仍高亮“全部资料”的状态。
- 修复浏览器禁止读取 `localStorage` 属性时在应用初始化阶段抛错。
- 恢复经典风格原有导入/新建按钮宽度比例。
- 扩大外观弹窗的可用滚动区域，修复普通手机中卡片被截断和主题标签孤字换行。
- 测试等待系统主题事件及异步资料刷新后再断言，避免把尚未完成的界面更新当作功能失败。

## 冻结交付

独立审查提交：`bcdfdc17b2bb2b94bbebb113c65e5fddc022f182`。共 69 个源文件变化（Android 25、Web 9、iOS 35），交付逐文件 SHA-256 已与实际工作区回读一致；Git bundle 校验及补丁反向应用检查通过。没有提交或覆盖主工作区中其他任务的历史修改。

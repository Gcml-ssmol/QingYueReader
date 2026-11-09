# 晴阅 iPhone / iPad App 工程 0.1.0

这是可交给 Mac 开发者继续编译、签名的完整工程，优先适配 iPad，兼容 iPhone。
**本次交付不是 IPA，也没有经过 Xcode 编译或 Apple 真机验证。**

界面和阅读引擎来自晴阅网页版 0.2.1 的本地源码快照，使用 Capacitor 8 封装为独立 App。
默认采用受 Codex 启发的中性色界面，可在「设置 → 外观」切回经典原版，并选择跟随系统、浅色或深色。
程序、字体、PDF 阅读器和批注资源内建，不需要先打开网站，也没有配置远程启动 URL。
这仍使用 WKWebView 绘制阅读界面；不等同于将 Android 全部功能改写成 Swift。

## 已包含

- iPhone / iPad 通用 Xcode 工程；最低 iOS / iPadOS **18.0**，横屏与竖屏。
- iPad 常驻文件夹栏、嵌套文件夹、未分类资料、回收站、页面缩略图栏。
- PDF 导入、阅读、书签、50%–300% 缩放、多页空白笔记和手动增加页数。
- 画笔、荧光笔、局部橡皮、独立工具样式、文字框与 TXT 内容导入。
- 含中文文字和批注的 PDF 导出；原生 Filesystem 分块保存和系统分享入口。
- `.qyweb` 整库备份与保留原有资料的恢复，支持与当前网页版手动互相转移。
- App 图标、隐私清单、依赖锁文件、源快照校验、回归测试和 Mac 验收脚本。

网页版与 App 的本机数据库**互不共享**，iPad 与 iPhone 也不会自动同步。
首次迁移需在网页版导出 `.qyweb`，然后在 App「设置 → 从备份恢复」导入。
不支持直接恢复 Android `.qyb`；可先从 Android 导出 PDF。

首版限制：单 PDF 40 MB、最多 3000 页；空白笔记最多 200 页；文字框 8000 字符，
TXT 文件 64 KiB；备份内原始文档合计 100 MiB。原生导出允许至 105 MiB（含封装开销）。
OCR、EPUB 原件、自动云同步、原生 Apple Pencil 压感和草稿页重排尚未移植到此 App。

## 在 Mac 上打包

需要 macOS、**Xcode 26 或更新版本**、Node.js 22 或更新版本。
首次安装依赖及解析 Swift Packages 需要联网。没有内置开发者账号、证书或签名私钥。

在项目根目录运行：

```sh
npm install --global pnpm@11.19.0
pnpm install --frozen-lockfile
pnpm run sync
pnpm test
pnpm run open
```

`sync` 会构建 `www/`、同步原生插件，并设置 iOS 18、版本号、隐私资源和可移植依赖路径。
日常更新使用 `pnpm run sync`；不要只执行 `cap sync`，因为还需要项目配置步骤。
`www/` 和 `ios/App/App/public/` 是生成文件，请改 `src/` 或构建脚本后重新同步。

Xcode 操作：

1. 打开 `ios/App/App.xcodeproj`，选择 App target 与 App scheme。
2. 等待 Swift Packages 完成解析。工程采用 SPM，无需 CocoaPods。
3. 在 Signing & Capabilities 选择自己的 Team；将默认 Bundle Identifier
   `cn.qingyue.ios` 改成你账号可使用的唯一标识，并同步修改 `capacitor.config.json` 的 `appId`。
   一旦正式安装后不要随意改变 App 标识或本地 scheme/hostname，否则原有本机资料可能无法读取。
4. 先选 iPad / iPhone 模拟器运行，再连接真实设备运行。首次使用免费个人 Team 的
   本机调试需要按 Xcode 的设备提示设置，不是对外分发渠道。
5. 加入 Apple Developer Program 后才继续通过 Archive、App Store Connect、
   TestFlight 或 App Store 分发；签名与审核以 Apple 当时要求为准。

不带签名的模拟器构建检查可运行：

```sh
bash scripts/verify-macos.sh
```

它只编译，不上传、不发布、不生成可分发 IPA。本机 Windows 没有运行这一步。

## 文件保存与备份

「保存到 App 文件」把导出副本写入 App Documents 下的：

```text
QingYueExports/<每次导出的唯一编号>/<文件名.pdf 或 .qyweb>
```

项目已启用系统「文件」App 访问，可在「我的 iPad / iPhone → 晴阅」中查找。
「系统分享 / 存储到文件」使用同一份已保存副本；取消分享不会删除副本。
分块大小 256 KiB，避免先将整个大文件转成 base64。

资料和工具设置保存在本机；App 没有账号、遥测、上传或服务器同步。
**卸载 App 会删除 App 内资料及导出副本。** 请通过系统分享定期把 `.qyweb`
备份存到 App 以外的位置。此说明也显示在 App 设置内。

## 已运行的检查

- Node 导出测试：跨分块字节一致、空文件、文件名清理、失败清理、并发保护、大小限制、连续导出、分享取消。
- 工程检查：内建资源哈希与 Xcode public 副本一致、iPhone/iPad 目标、版本与插件路径、Files 权限和隐私资源。
- 本地桌面 Chromium：iPad 横竖屏和 iPhone 尺寸、缩略图与加页、备份互转、原生导出 UI（原生插件以 mock 模拟）。
- PDF 回归：四种旋转、中文文本提取、荧光笔重叠与圆点、局部橡皮、缩放边界、损坏备份拒绝及并发保存保护。

这些检查不能代替 Xcode / iOS 真机测试。具体待验项目见 `APPLE-DEVICE-CHECKLIST.md`。
浏览器测试需安装 Playwright 和 Edge，或调整测试脚本的浏览器通道。
以本地静态服务器提供 `www/` 后，通过 `QY_TEST_URL` 指定地址执行 tests 下的 `.cjs` 脚本。
`QY_PLAYWRIGHT_MODULE` 可指向已有 Playwright 安装位置，不属于 App 运行依赖。

## 工程目录

- `ios/`：Xcode、Swift 入口、隐私清单、图标和内建 public 资源。
- `source-web/`：晴阅网页版 0.2.1 固定快照；`SOURCE.json` 记录来源和逐文件哈希。
- `src/`：原生桥、文件导出、导出交互和启动检查。
- `scripts/`：资源构建、原生配置和 Mac 构建检查。
- `tests/`：导出、工程、界面和数据完整性测试。
- `www/`：可重新生成的 App 前端完整资源。

源快照来自提交 `cdce83dab14e260336ee8318cff882a1d9f145dd`。
不包含个人 PDF、笔记、登录信息、Android 签名私钥或 Apple 证书。

官方说明：[Capacitor 环境](https://capacitorjs.com/docs/getting-started/environment-setup)、
[iOS 项目](https://capacitorjs.com/docs/ios)、
[文件系统](https://capacitorjs.com/docs/apis/filesystem)、
[Apple 分发](https://developer.apple.com/documentation/Xcode/distributing-your-app-for-beta-testing-and-releases)。

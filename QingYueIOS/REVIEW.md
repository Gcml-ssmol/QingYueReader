# 总审查记录

交付对象：QingYueIOS 0.1.0，iPad 优先的 iPhone/iPad App 工程。

结论：源码、网页资源构建及本地回归通过，可交给 Mac 开发者继续编译验证。
**不作为已通过 iOS 运行验收或可直接安装的 IPA 交付。**

子代理（GPT-6 Sol low）完成了独立原生导出内核，并复核了官方插件实现；
总窗口负责封装接入、Xcode 工程、审查修正、完整性检查及交付。

审查修正：Filesystem 在根目录已存在时，即使 recursive=true 仍报
OS-PLUG-FILE-0010。现在仅忽略导出根目录的这一错误；每次新建的唯一 ID 目录
仍拒绝冲突。失败仅清理本次新建目录，旧导出与原始文档不会被删除。
独立复查已核对锁定的 ion-ios-filesystem 1.1.1 源码的 base64 与追加写入语义。

验证结果：

- 8/8 导出单元测试；3/3 工程结构与逐文件哈希测试。
- Xcode PBX 文件由 xcode 解析器成功解析，隐私清单在 App Resources 中；
  Info.plist / PrivacyInfo.xcprivacy 由 plistlib 成功解析。
- 本地 Chromium 通过 iPad 1194×834、834×1194 与 iPhone 390×844 布局、
  文件夹树、页面栏收合重绘、缩略图、选页、加页和备份双向转移检查。
- Native UI 测试使用模拟 Filesystem/Share：PDF 和 .qyweb 输出正确，保存后分享
  复用同一副本，取消分享保留文件。
- PDF 完整性检查通过 0/90/180/270 度页面、中文文本提取、荧光笔、圆点、
  局部擦除、缩放范围、原件哈希不变、过期写入拒绝、损坏备份原子拒绝、文件夹循环拒绝。
- 最终 App 资源 228 个，已逐文件校验与 Xcode public 副本一致。

发布边界：未执行 Xcode、Swift 编译、Apple 签名、IPA 构建、TestFlight 上传或真机测试。
系统文件选择器、原生分享弹窗、WKWebView worker/WASM/IndexedDB 与磁盘不足
行为仍以 `APPLE-DEVICE-CHECKLIST.md` 为后续验收依据。

Android 1.13.0 和已私人发布的网页 0.2 未改动。本工程不自动加入 GitHub，也不包含
任何用户文档、网站登录状态、签名私钥或 Apple 证书。

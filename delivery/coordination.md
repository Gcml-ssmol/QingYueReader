# 晴阅 1.8.0 总整合记录

总窗口：01a0c923-3730-7632-b7e1-aa879c4227f8。
基线提交：579315de66c5b6efb8fadab35bea7eb05557abd8。
用户选择：尽量覆盖全部建议；暂不连接Android设备，先代码审查和APK。
六个新开发任务均指定 gpt-5.6-sol / ultra；只在独立Git工作树开发。

| 模块 | 任务 | 已解析ID/创建ID | 工作树 | 状态 |
| --- | --- | --- | --- | --- |
| library | 晴阅开发：文档库、恢复与导入 | 01a0c935-3505-7453-aaa2-0250ca631f53 / client-new-thread:ba7d4395-f61d-4868-aacd-beae28d5ab93 | C:/Users/xsh12/.codex/worktrees/a7a1/笔记app | 开发中，API已草拟 |
| notes | 晴阅开发：多页笔记与编辑器 | 01a0c93b-fc23-74e1-8183-aef0b2d24ce0 / client-new-thread:22423509-8ef7-4cda-b756-0e2f419ecdda | C:/Users/xsh12/.codex/worktrees/fd7e/笔记app | 开发中，API已草拟 |
| reader | 晴阅开发：PDF阅读、检索与导出 | client-new-thread:fbc924a6-a8d8-4ed1-a82f-fc88ad530473 | C:/Users/xsh12/.codex/worktrees/17b0/笔记app | 开发中 |
| learning | 晴阅开发：双栏、录音与复习 | client-new-thread:3dffcc4d-7882-4075-ba9b-8dcac8bbb340 | C:/Users/xsh12/.codex/worktrees/bb89/笔记app | 开发中 |
| recognition | 晴阅开发：离线OCR与全库搜索 | 01a0c93d-8b09-7b53-8a0e-ecef4d737932 / client-new-thread:a95dbd58-1885-42ea-9ddc-7c60a056c1fb | C:/Users/xsh12/.codex/worktrees/c914/笔记app | 开发中，Tesseract4Android离线chi_sim+eng |
| sync | 晴阅开发：可选同步与数据迁移 | 01a0c93f-33e2-7140-a348-68674a16f79b / client-new-thread:1817d296-fb87-4e27-b4e0-6d09e40a5e52 | C:/Users/xsh12/.codex/worktrees/be27/笔记app | 开发中 |

总窗口本地负责：AppColors/外观设置/ThemedActivity、Manifest注册权限、跨模块入口和契约适配、版本号、回归与APK构建、同签名校验、交付文件和审查结论。

整合前检查：

- 单份笔记可编辑原档必须包含图片，不能只发.qnote二进制。
- 备份已锁定manifest.json（不是早期properties草案），schema1；同步依赖ArchiveSource，不独立解析业务备份。
- 备份稳定指纹不含打包时间；凭据/Keystore/staging/缓存不得入包。
- 笔记v1读、v2写，旧API仍作用当前页；多页检索使用pageText/plainText。
- 恢复ID冲突要重映射学习来源/卡片/录音关联，不能只移动文件。
- PDF批注导出保留文字层，夜间外观不改导出或存储笔迹颜色。
- AppColors变成动态显示调色板，Activity避免static-final颜色缓存，纸张显示使用PAPER而导出使用EXPORT_PAPER。
- 最终所有独立Activity改用ThemedActivity并注册；模块本地编译不能替代总包整合验证。
- 用户未授权测试设备安装（暂不连接）；本次不声称触摸、录音、OCR设备运行已通过。

原签名只读来源：C:/Users/xsh12/Documents/Codex/2026-09-22/new-chat/work/android-build/local-test.keystore。
最终签名须与1.7.0证书SHA256 be5604ac60eca81271ec761830359cae7bb8fb42a2dfa4a00325f8dc8ae3323f 一致；密钥不加入源码包。

已创建本任务10分钟跟进心跳，automationId=automation，名称“晴阅开发审查与整合”，最终交付后删除。不在无变化时通知。
总回归脚本已由sol ultra子代理完成并运行基线：PASS=15 FAIL=0 SKIP=0（计数包含纯Java编译）。根窗口主题新增类全量Java编译通过，尚未全局接入。

交付格式：outputs/1.8.0/qingyue-pdf-1.8.0.apk、QingYuePDF/、QingYuePDF-1.8.0-source.zip、验证说明-1.8.0.txt，以及功能完成矩阵/审批记录。

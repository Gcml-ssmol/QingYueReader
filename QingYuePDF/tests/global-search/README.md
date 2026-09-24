# Global search desktop regression

Run from the project root:

```powershell
.\tests\global-search\run.ps1 -BuildDirectory .\work\global-search-tests
```

This exercises the Android-independent persistent index and the real checked-in PDFBox Android
AAR through lightweight desktop Android shims. It covers PDF/note/OCR/summary source composition,
page and jump metadata, incremental reuse, changed/deleted invalidation, cancellation without a
partial commit, bounded/corrupt input, persistence, and English/Chinese PDF text extraction.
The channel-filter regression includes 230 matching document titles plus PDF, note, and OCR
matches. It verifies filtering before the 200-result cap, per-channel truncation flags, preserved
page/anchor metadata, persisted-index queries, and compatibility of the original search overloads.

It does **not** validate `GlobalSearchActivity`, Android lifecycle behavior, touch interaction,
accessibility rendering, device storage behavior, or OCR recognition quality. Those require an
Android device or emulator; the shims are only enough to exercise PDF text extraction on the JVM.
# 主线 1.12 接入约定

Manifest 注册 `.GlobalSearchActivity`（`exported=false`），主窗口使用
`startActivity(new Intent(this, GlobalSearchActivity.class))` 打开搜索页。搜索结果以
`docId` 和从 0 开始的 `page` 返回现有 MainActivity；主控负责核验资料仍在库中。
内建笔记/OCR provider 由 `GlobalSearchActivity.onCreate` 安装，无须在
`QingYueApplication` 注册。其他模块可调用 `GlobalSearchProviderRegistry.register`。

当前搜索文库标题、PDF 文本层、单页笔记 `TEXT` 项、阅读器文本框及现有 `OcrSidecar`。
阅读器手写批注尚无文字 provider，本轮不索引。OCR 侧车只在实际 PDF
SHA-256 匹配时进入索引；v2 侧车另校验原件 identityHash。旧 v1 转换件在搜索中
不显示，避免 PDF 字节变更后把旧文字误当新结果。用户 OCR 数据仍使用主线
per-document 存储。

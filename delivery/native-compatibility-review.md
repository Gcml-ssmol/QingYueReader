# OCR 原生库 16 KiB 对齐预审

2026-09-22，总窗口检查 recognition 工作树中官方 Tesseract4Android 4.9.0 AAR。此记录是文件布局审查，不能代替设备执行。

Android 官方要求检查原生 ELF LOAD 对齐以及未压缩库在 APK 中的对齐；[构建说明](https://developer.android.com/guide/practices/page-sizes)。实际 [Bionic RELRO 保护实现](https://android.googlesource.com/platform/bionic/+/android16-qpr2-release/linker/linker_phdr.cpp#1391) 将保护区起点向下、终点向上取整到系统页边界。因此仅以 RELRO 终点不是 16 KiB 整数倍判定不兼容，不能充分证明存在错误。还需判断取整后的区域是否覆盖实际需要写入的数据。

官方页面目前同时提供 RELRO 终点整除 0x4000 的保守检查，并说明覆盖后续可写区域的故障例子。本次对 AAR 的接受依据属于结合实际 LOAD 布局与加载器源码的静态判断，不能表述成“所有官方检查或设备验证通过”；应保留终点余数与未实测的说明。main 分支加载器也仍按页边界扩展保护区域。

根窗口解析 AAR 中 8 个 64 位 ELF 的程序头，所有 PT_LOAD 的 p_align 均为 0x4000。示例：

| 库 | RELRO 地址范围 | 16 KiB 取整范围 | 后续可写 LOAD 起点 |
| --- | --- | --- | --- |
| arm64-v8a/libleptonica.so | 0x2B9B10–0x2BE000 | 0x2B8000–0x2C0000 | 0x2C18E0 |
| x86_64/libtesseract.so | 0x412880–0x425000 | 0x410000–0x428000 | 0x428E40 |

上述示例取整终点均未触及后续可写 LOAD；部分 jpeg/png 库的 RELRO 覆盖整个唯一的 RW LOAD，之后无另一个 RW LOAD。已要求 recognition 任务将检查依据改为实际映射范围、offset/vaddr 同余及保护区重叠，再判断是否需要重编，不能因简单取模判据引入不必要的自编译依赖。

待最终交付检查：固定依赖哈希、全部 ABI 文件、原生依赖闭包、最终 APK 压缩方式及 zipalign、最终审核脚本输出。Android 设备运行仍未验证。

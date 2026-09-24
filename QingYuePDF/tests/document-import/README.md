# Office / text import verification (1.7.0)

Run from the workspace with:

```powershell
.\outputs\1.7.0\QingYuePDF\tests\document-import\run.ps1 -BuildDirectory .\work\document-import-test
```

`DocumentImportTest` runs the **same production `DocumentText` extractor**, not a mock.
It passes 23 checks covering genuine Word 97 binary DOC, Word 6 DOC, DOCX, a HWPF-written
Chinese DOC, UTF-8 / UTF-16 BOM / GB18030 text, paragraphs/tabs/line breaks, deleted
DOCX text, renamed files identified by content, corrupt OLE, fake DOC, DTDs in UTF-8
and UTF-16, empty/binary TXT, shared-text normalization and limits, cooperative
cancellation, and text/decompression limits. `ImportSessionTest` adds 28 checks for
state replay, progress, exclusive result claims, failed-commit retry, idempotent completion,
discard, and cancellation. Sample document fixtures
come from Apache POI `REL_5_5_1`; exact URLs and SHA-256 values are in
`fixtures/sources.json`. Apache license/NOTICE files are bundled in `third-party/licenses`.
The generated Chinese DOC and other adversarial fixtures stay in the supplied build directory.

The native PDF conversion compiles against Android API 35. It uses `PdfDocument`
and `StaticLayout` on a background worker, A4 pages, native text drawing, 400-page
limit and bounded 8K-character layouts. Word documents are imported as **text reflow**;
original typography, page breaks, tables-as-layout, embedded pictures and shapes are
not reproduced. Existing PDF files retain their original content.

The direct APK build backports required Java 8/11 APIs (`java.time`, streams,
`java.nio.file`) with Google's `desugar_jdk_libs_nio:2.1.5`, D8 and L8; Gradle declares
the same core-library desugaring dependency. POI uses `WordExtractor.getParagraphText()`
from a bounded `InputStream`, followed by footnotes/endnotes/textboxes. It does not
use desktop Word-to-HTML/FO/AWT converters or memory-mapped file buffers.

Validation completed:

- JVM tests passed with the normal runtime and with multi-release JAR support disabled
  (`-Djdk.util.jar.enableMultiRelease=false`, the base classes used by Android's D8).
- Android-target Java compilation, D8 library compilation and L8 backport compilation passed.
- D8 warnings concern unused desktop AWT converters, optional OSGi adapters and a
  `CleanerUtil` method for memory-mapped files; the import path uses in-memory
  `ByteArrayBackedDataSource`, never the cleaner. A loaded-class trace of real DOC
  extraction did not load AWT or `CleanerUtil`.
- Android's ExpatReader implementation accepts both external-entity SAX flags set to
  false. DTD declarations are rejected before parsing; external entity resolution is blocked.

There is no connected Android device or emulator in this environment. Touch UI,
Android runtime DOC import and generated-PDF text search still require device checks;
the JVM checks are not a substitute for those checks.

Primary implementation references:

- https://poi.apache.org/components/document/quick-guide.html
- https://poi.apache.org/download.cgi
- https://developer.android.com/studio/write/java8-support
- https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/org/apache/harmony/xml/ExpatReader.java

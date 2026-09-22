# PDF search parser smoke test

From the project directory, with Java 17 on PATH:

```powershell
.\tests\pdf-search-smoke\run.ps1 -BuildDirectory .\build-test
```

This runs the production `PdfTextSearch` and `SearchText` with the actual bundled
PDFBox Android AAR and its assets, against local fixture PDFs. It verifies English
case-insensitive search, Chinese text using the Adobe-GB1 CMap, zero-based page
navigation indices, match counts, blank pages, no-match behavior, the 200-page
result limit, monotonic progress and replacing an in-progress query.

The tiny `android/` classes here are **test-only desktop shims**, outside the app
source set. They provide logging, asset reads, scheduling and the small geometry
surface needed to exercise extraction. They are never included in an APK. This
test does not verify Android rendering, native scheduling, UI or device behavior.
`sample.pdf`, `many.pdf`, and `empty.pdf` are synthetic, locally generated fixtures
containing no user documents. Each run uses a fresh directory and retains it for
inspection.

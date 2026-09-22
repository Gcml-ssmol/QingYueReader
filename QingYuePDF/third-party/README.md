# Offline PDF text search dependencies

These pinned, unmodified artifacts are checked in so the local SDK build does not
need network access. `dependencies.json` records their Maven Central URLs, byte
lengths and SHA-256 hashes. `build-apk.ps1` verifies those hashes before building.

| Artifact | Version | License |
| --- | --- | --- |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | Apache 2.0 and notices for bundled resources |
| `org.bouncycastle:bcprov-jdk15to18` | 1.72 | Bouncy Castle license |
| `org.bouncycastle:bcpkix-jdk15to18` | 1.72 | Bouncy Castle license |
| `org.bouncycastle:bcutil-jdk15to18` | 1.72 | Bouncy Castle license |

The three Bouncy Castle versions match PDFBox Android's published POM. They are
included for its encryption-related references; this app does not sign documents
or expose certificate operations. Optional JPEG2000 rendering dependencies are
not needed by the text extraction path. PDF display continues to use Android's
native renderer.

The original PDFBox AAR contains all glyph lists, font metrics and CJK CMaps. The
build script packages its `assets/` tree, the dependency JAR resources and every
generated DEX file. Android 6 supports those DEX files natively. Gradle consumes
the same local AAR/JARs. All files in `licenses/` are also included in the APK.

Upstream references:

- https://github.com/TomRoush/PdfBox-Android/tree/v2.0.27.0
- https://repo.maven.apache.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/
- https://github.com/bcgit/bc-java/tree/r1rv72
- https://github.com/liberationfonts/liberation-fonts/tree/2.1.5

`licenses/PDFBox-Android-LICENSE.txt` and its NOTICE are copied from that PDFBox
tag, and `licenses/BouncyCastle-LICENSE.html` from that Bouncy Castle tag. The AAR
bundles Liberation Sans 2.1.5; its SIL Open Font License and copyright notice are
included as `licenses/Liberation-Fonts-LICENSE.txt`. CMap and glyph files also
retain their original embedded notices.

Search handles extractable text layers locally, one page at a time. It does not
perform OCR. Image-only scans, text converted to outlines and PDFs with missing
Unicode mappings may not yield searchable words. The reader still opens them.

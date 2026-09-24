# Bundled OCR PDF font

`app/src/main/assets/ocr/ocr-font.ttf` is a static TrueType font made from two official Android Open Source Project fonts in `platform/frameworks/base`, branch `android11-security-release`, directory [`data/fonts`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-security-release/data/fonts/):

| Input | Upstream Git blob | SHA-256 | Size |
| --- | --- | --- | ---: |
| [`DroidSansFallback.ttf`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-security-release/data/fonts/DroidSansFallback.ttf) | `1099b177c881c96bf298989dcdca5fda7841133d` | `21b96a0377f067833a93af3082eb28d4ffab7a8cd46bfd513286f1d64b7b0949` | 3,451,900 bytes |
| [`DroidSansMono.ttf`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-security-release/data/fonts/DroidSansMono.ttf) | `b7bf5b4aa8ad5a8c8036653734d83006c6a376ee` | `db19a1fdaba41cc4a2fec0330e5c15e71c6dd68a3ef074f4f28268828b45c862` | 108,128 bytes |

The [upstream font directory](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-security-release/data/fonts/) contains `MODULE_LICENSE_APACHE2`, and its `README.txt` says the directory's fonts are Apache 2.0 licensed. The upstream `NOTICE` and full Apache 2.0 terms are preserved in `third-party/licenses/DroidSansFallback-AOSP-NOTICE.txt`. Both unchanged source fonts are kept beside this file.

`merge-aosp-fonts.py` uses fontTools **4.65.0** to scale DroidSansMono from 2048 to 256 units per em, add its glyphs and missing Unicode mappings to DroidSansFallback, rename the derivative family `QingYue OCR Droid Sans`, and fix the metadata timestamp for reproducible bytes. Regenerate with:

```text
python -m pip install fonttools==4.65.0
python third-party/ocr/merge-aosp-fonts.py third-party/ocr/DroidSansFallback.ttf third-party/ocr/DroidSansMono.ttf app/src/main/assets/ocr/ocr-font.ttf
```

Final `ocr-font.ttf`: **3,585,628 bytes**, SHA-256 `80de08bf48857ccf91462dacc629ead85e48aa39db5a24200131b2f422e965e4` (confirmed identical across two regeneration runs).

`tests/recognition/run-pdf-export.ps1 -FontFile app/src/main/assets/ocr/ocr-font.ttf` passed: PDFBox embedded invisible Chinese and English text into three pages, extracted the exact mixed-script text on 0°, 90°, and 270° pages, verified crop/rotation positions, and confirmed the original PDF bytes were unchanged. This validates this exact output font with the project's PDFBox Android 2.0.27.0 dependency; device OCR and APK packaging remain separate checks.

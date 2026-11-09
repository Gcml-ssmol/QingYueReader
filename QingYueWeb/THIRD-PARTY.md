# Third-party components

Runtime copies are kept in `dist/vendor`; no CDN is contacted during reading.

| Component | Version | Upstream | License notice |
| --- | --- | --- | --- |
| PDF.js legacy browser distribution, cmaps, standard fonts, wasm | 5.6.205 | https://github.com/mozilla/pdf.js | dist/licenses/pdfjs.txt |
| pdf-lib | 1.17.1 | https://github.com/Hopding/pdf-lib | dist/licenses/pdf-lib.txt |
| @pdf-lib/fontkit | 1.1.1 | https://github.com/Hopding/fontkit | dist/licenses/fontkit-README.md, fontkit-package.json, fontkit-MIT.txt |
| JSZip | 3.10.1 | https://github.com/Stuk/jszip | dist/licenses/jszip.txt |
| QingYue OCR Droid Sans (web derivative) | local modified font | Android Open Source Project DroidSansFallback / DroidSansMono | dist/licenses/DroidSansFallback-AOSP-NOTICE.txt, DroidSansFallback-AOSP-MODIFICATIONS.txt |

The Android font provenance is preserved as `dist/licenses/FONT-PROVENANCE-ANDROID.md`. The web copy additionally removes the inconsistent vertical metric tables `vhea` / `vmtx`, preserving glyphs, Unicode mapping and horizontal metrics; table checksums are regenerated. Reproduce this last conversion with `node font-preparation-reference.cjs input.ttf output.ttf`. PDF export embeds the complete font (`subset:false`) because subsetting this merged font produced visible missing glyphs despite correct text extraction. The Android asset is unchanged.

# Simplified Chinese OCR model

- Upstream: Tesseract `tessdata_best`, commit `e12c65a915945e4c28e237a9b52bc4a8f39a0cec`
- Source: https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/chi_sim.traineddata
- File: `models/tessdata/chi_sim.traineddata`
- Bytes: `13077423`
- SHA-256: `4fef2d1306c8e87616d4d3e4c6c67faf5d44be3342290cf8f2f0f6e3aa7e735b`
- License: Apache-2.0, https://github.com/tesseract-ocr/tessdata_best/blob/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/LICENSE

The upstream repository calls this its best, most accurate LSTM model family. Its documentation says it is slower than `tessdata_fast` and requires the LSTM engine. This is a model-family choice, not a measured accuracy claim for QingYuePDF documents. The bundled English model remains unchanged.

`OcrModelInstaller` checks the installed model's size and SHA-256 on every `ensure` call. Existing installs with the former fast model fail that check and are atomically replaced with this packaged model before recognition.

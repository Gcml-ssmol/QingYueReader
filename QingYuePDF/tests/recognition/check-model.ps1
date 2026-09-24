$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '..\..')
$model=Join-Path $root 'third-party\ocr\models\tessdata\chi_sim.traineddata'
$installer=Get-Content (Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrModelInstaller.java') -Raw
$manifest=Get-Content (Join-Path $root 'third-party\ocr\SHA256SUMS') -Raw
$bytes=(Get-Item -LiteralPath $model).Length
$hash=(Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash.ToLowerInvariant()
if ($installer -notmatch [regex]::Escape("new Model(`"chi_sim.traineddata`", ${bytes}L")) { throw 'Installer size does not match model' }
if ($installer -notmatch [regex]::Escape($hash)) { throw 'Installer hash does not match model' }
if ($manifest -notmatch [regex]::Escape("$hash  models/tessdata/chi_sim.traineddata")) { throw 'Manifest hash does not match model' }
if ($hash -eq 'a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730') { throw 'Old fast model is still packaged' }
Write-Output 'OCR model bytes, installer pin, and manifest match'

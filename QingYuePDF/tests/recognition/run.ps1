param([string]$BuildDirectory = (Join-Path $PSScriptRoot '../../work/recognition-tests'))
$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '..\..')
$classes=Join-Path $BuildDirectory 'classes'
& (Join-Path $PSScriptRoot 'check-model.ps1')
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 -d $classes (Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrHocr.java') (Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrCancellation.java') (Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrSelection.java') (Join-Path $PSScriptRoot 'OcrHocrTest.java') (Join-Path $PSScriptRoot 'OcrCancellationTest.java') (Join-Path $PSScriptRoot 'OcrSelectionTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $classes cn.qingyue.pdf.OcrHocrTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $classes cn.qingyue.pdf.OcrCancellationTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $classes cn.qingyue.pdf.OcrSelectionTest
exit $LASTEXITCODE

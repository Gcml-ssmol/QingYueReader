param([Parameter(Mandatory=$true)][string]$FontFile)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$font=(Resolve-Path -LiteralPath $FontFile).Path
$run=Join-Path $root ('build\ocr-pdf-export-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $run,(Join-Path $run 'classes') | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $root 'third-party\pdfbox-android-2.0.27.0.aar'),(Join-Path $run 'pdfbox'))
$classpath=(@((Join-Path $run 'classes'),(Join-Path $run 'pdfbox\classes.jar'))+@(Get-ChildItem (Join-Path $root 'third-party') -Filter '*.jar' | ForEach-Object FullName)) -join [IO.Path]::PathSeparator
$sources=@(Get-ChildItem (Join-Path $root 'tests\pdf-search-smoke\android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@((Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrCancellation.java'),(Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrHocr.java'),(Join-Path $root 'app\src\main\java\cn\qingyue\pdf\OcrPdfExporter.java'),(Join-Path $PSScriptRoot 'OcrPdfExportTest.java'))
& javac -encoding UTF-8 --release 8 -classpath $classpath -d (Join-Path $run 'classes') @sources
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java "-Dsmoke.assets=$(Join-Path $run 'pdfbox\assets')" "-Docr.test.root=$run" "-Docr.test.font=$font" -cp $classpath cn.qingyue.pdf.OcrPdfExportTest
exit $LASTEXITCODE

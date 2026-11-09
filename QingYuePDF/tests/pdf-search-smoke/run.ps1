param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$BuildDirectory=(Resolve-Path -LiteralPath $BuildDirectory).Path
$runDirectory=Join-Path $BuildDirectory ('pdf-smoke-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory,(Join-Path $runDirectory 'classes'),(Join-Path $runDirectory 'cache') | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$deps=Join-Path $projectRoot 'third-party'
[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $deps 'pdfbox-android-2.0.27.0.aar'),(Join-Path $runDirectory 'pdfbox'))
$classpath=(@((Join-Path $runDirectory 'classes'),(Join-Path $runDirectory 'pdfbox\classes.jar'))+@(Get-ChildItem -LiteralPath $deps -Filter '*.jar' | ForEach-Object FullName)) -join [System.IO.Path]::PathSeparator
$sourceFiles=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourceFiles+=@((Join-Path $PSScriptRoot 'PdfSearchSmoke.java'),(Join-Path $PSScriptRoot 'PdfExportSmoke.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PdfTextSearch.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PdfTextGeometry.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PdfAnnotationExporter.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PdfTextBoxes.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\ReaderDocument.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkPage.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkStrokeRenderer.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\NightInk.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkStore.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkHistoryRegistry.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PageMath.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\SearchText.java'))
& javac -encoding UTF-8 --release 8 -classpath $classpath -d (Join-Path $runDirectory 'classes') @sourceFiles
if($LASTEXITCODE -ne 0) { throw 'PDF search smoke compilation failed' }
& java "-Dsmoke.root=$runDirectory" "-Dsmoke.assets=$(Join-Path $runDirectory 'pdfbox\assets')" "-Dsmoke.fixtures=$PSScriptRoot" -cp $classpath cn.qingyue.pdf.PdfSearchSmoke
if($LASTEXITCODE -ne 0) { throw 'PDF search smoke failed' }
& java "-Dsmoke.root=$runDirectory" "-Dsmoke.assets=$(Join-Path $runDirectory 'pdfbox\assets')" "-Dsmoke.font=$(Join-Path $projectRoot 'app\src\main\assets\ocr\ocr-font.ttf')" "-Dsmoke.fixtures=$PSScriptRoot" -cp $classpath cn.qingyue.pdf.PdfExportSmoke
if($LASTEXITCODE -ne 0) { throw 'PDF export smoke failed' }

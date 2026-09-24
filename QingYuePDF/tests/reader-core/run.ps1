param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$BuildDirectory=(Resolve-Path -LiteralPath $BuildDirectory).Path
$run=Join-Path $BuildDirectory ('reader-core-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run,(Join-Path $run 'classes'),(Join-Path $run 'data') | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@((Join-Path $PSScriptRoot 'ReaderModuleTest.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\ReaderDocument.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\ReaderStore.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\ReaderSourceLink.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PageLayout.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PdfRenderPlan.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkPage.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkStore.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\InkHistoryRegistry.java'))
& javac -encoding UTF-8 --release 8 -d (Join-Path $run 'classes') @sources
if($LASTEXITCODE -ne 0){throw 'Reader core compilation failed'}
& java "-Dreader.test.root=$(Join-Path $run 'data')" -cp (Join-Path $run 'classes') cn.qingyue.pdf.ReaderModuleTest
if($LASTEXITCODE -ne 0){throw 'Reader core test failed'}

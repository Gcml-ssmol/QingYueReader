param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../work/pdf-epub-ui-test'))
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@((Join-Path $project 'app/src/main/java/cn/qingyue/pdf/EpubConversionCoordinator.java'),(Join-Path $project 'app/src/main/java/cn/qingyue/pdf/OcrCancellation.java'))
$sources+=@(Get-ChildItem -LiteralPath $PSScriptRoot -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@((Join-Path $project 'tests/backup-session/android/os/Handler.java'),(Join-Path $project 'tests/backup-session/android/os/Looper.java'))
& javac -encoding UTF-8 --release 8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'EPUB UI session test compilation failed'}
& java -cp $classes cn.qingyue.pdf.EpubConversionUiTest
if($LASTEXITCODE -ne 0){throw 'EPUB UI session test failed'}

param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$runDirectory=Join-Path (Resolve-Path -LiteralPath $BuildDirectory).Path ('thumbnail-lifecycle-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory,(Join-Path $runDirectory 'classes') | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@((Join-Path $PSScriptRoot 'ThumbnailLifecycleSmoke.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PdfThumbnailProvider.java'),(Join-Path $projectRoot 'app\src\main\java\cn\qingyue\pdf\PageMath.java'))
& javac -encoding UTF-8 --release 8 -d (Join-Path $runDirectory 'classes') @sources
if($LASTEXITCODE -ne 0){throw 'Thumbnail lifecycle compilation failed'}
& java -cp (Join-Path $runDirectory 'classes') cn.qingyue.pdf.ThumbnailLifecycleSmoke
if($LASTEXITCODE -ne 0){throw 'Thumbnail lifecycle smoke failed'}

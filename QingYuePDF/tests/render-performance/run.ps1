param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$run=Join-Path (Resolve-Path -LiteralPath $BuildDirectory).Path ('render-performance-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=(Join-Path $PSScriptRoot 'RenderPerformanceTest.java')
foreach($file in @('InkPage.java','InkStrokeRenderer.java','PdfRenderPlan.java','NightInk.java')){$sources+=Join-Path $projectRoot ('app/src/main/java/cn/qingyue/pdf/'+$file)}
& javac -encoding UTF-8 --release 8 -d $run @sources
if($LASTEXITCODE -ne 0){throw 'Render performance compilation failed'}
& java -cp $run cn.qingyue.pdf.RenderPerformanceTest
if($LASTEXITCODE -ne 0){throw 'Render performance regression failed'}

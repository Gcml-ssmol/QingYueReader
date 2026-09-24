param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 --release 8 -d $classes (Join-Path $projectRoot 'app/src/main/java/cn/qingyue/pdf/ReaderSidebarLayout.java') (Join-Path $PSScriptRoot 'NavigationLayoutTest.java')
if($LASTEXITCODE -ne 0){throw 'Navigation layout compilation failed'}
& java -cp $classes cn.qingyue.pdf.NavigationLayoutTest
if($LASTEXITCODE -ne 0){throw 'Navigation layout regression failed'}

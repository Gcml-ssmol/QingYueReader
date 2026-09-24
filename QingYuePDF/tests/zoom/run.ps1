param([string]$BuildDirectory = (Join-Path $PSScriptRoot '../../../work/zoom-tests'))
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes = Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 --release 8 -d $classes (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/ZoomLimits.java') (Join-Path $PSScriptRoot 'ZoomLimitsTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Zoom test compilation failed' }
& java -cp $classes cn.qingyue.pdf.ZoomLimitsTest
if ($LASTEXITCODE -ne 0) { throw 'Zoom test failed' }

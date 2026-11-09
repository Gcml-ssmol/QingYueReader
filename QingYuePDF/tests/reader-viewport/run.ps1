param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../work/reader-viewport-tests'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
& javac -encoding UTF-8 --release 8 -d $BuildDirectory `
    (Join-Path $root 'app/src/main/java/cn/qingyue/pdf/ReaderViewportMath.java') `
    (Join-Path $PSScriptRoot 'ReaderViewportMathTest.java')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp $BuildDirectory cn.qingyue.pdf.ReaderViewportMathTest
exit $LASTEXITCODE

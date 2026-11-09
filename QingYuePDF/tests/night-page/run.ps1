param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../work/night-page-tests'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
& javac -encoding UTF-8 --release 8 -d $BuildDirectory `
    (Join-Path $root 'app/src/main/java/cn/qingyue/pdf/NightInk.java') `
    (Join-Path $PSScriptRoot 'NightInkTest.java')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp $BuildDirectory cn.qingyue.pdf.NightInkTest
exit $LASTEXITCODE

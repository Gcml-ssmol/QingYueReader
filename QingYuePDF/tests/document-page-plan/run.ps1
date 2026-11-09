param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../work/document-page-plan-tests'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
& javac -encoding UTF-8 --release 8 -d $BuildDirectory `
    (Join-Path $root 'app/src/main/java/cn/qingyue/pdf/DocumentPagePlan.java') `
    (Join-Path $root 'app/src/main/java/cn/qingyue/pdf/ImportVariantKey.java') `
    (Join-Path $PSScriptRoot 'DocumentPagePlanTest.java') `
    (Join-Path $PSScriptRoot 'ImportVariantKeyTest.java')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp $BuildDirectory cn.qingyue.pdf.DocumentPagePlanTest
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp $BuildDirectory cn.qingyue.pdf.ImportVariantKeyTest
exit $LASTEXITCODE

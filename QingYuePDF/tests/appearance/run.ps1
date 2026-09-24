param([Alias('BuildDirectory')][string]$OutputDirectory = (Join-Path $PSScriptRoot '../../work/appearance-tests'))
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot 'AppearancePreferencesTest.java'
$sources += Join-Path $PSScriptRoot '../../app/src/main/java/cn/qingyue/pdf/AppColors.java'
& javac -encoding UTF-8 --release 8 -d $OutputDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Appearance preference compilation failed' }
& java -cp $OutputDirectory cn.qingyue.pdf.AppearancePreferencesTest
if ($LASTEXITCODE -ne 0) { throw 'Appearance preference regression failed' }

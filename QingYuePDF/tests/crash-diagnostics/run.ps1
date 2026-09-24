param([string]$BuildDirectory = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')) 'work/crash-diagnostics'))
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$classes = Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source = Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
$java = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$java += Join-Path $source 'CrashDiagnostics.java'
$java += Join-Path $source 'QingYueApplication.java'
& javac -encoding UTF-8 --release 8 -d $classes @java
if ($LASTEXITCODE -ne 0) { throw 'Crash diagnostics compilation failed' }
& java -cp $classes cn.qingyue.pdf.CrashDiagnosticsTest $BuildDirectory
if ($LASTEXITCODE -ne 0) { throw 'Crash diagnostics behavior failed' }

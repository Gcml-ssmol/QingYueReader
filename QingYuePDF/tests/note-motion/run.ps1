param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$stubs=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 8 -d $classes @stubs (Join-Path $projectRoot 'app/src/main/java/cn/qingyue/pdf/NoteMotion.java') (Join-Path $PSScriptRoot 'NoteMotionTest.java')
if($LASTEXITCODE -ne 0){throw 'Note motion compilation failed'}
& java -cp $classes cn.qingyue.pdf.NoteMotionTest
if($LASTEXITCODE -ne 0){throw 'Note motion regression failed'}

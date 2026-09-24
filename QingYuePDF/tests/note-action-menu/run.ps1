param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 --release 8 -d $classes (Join-Path $projectRoot 'app/src/main/java/cn/qingyue/pdf/NoteActionPlacement.java') (Join-Path $PSScriptRoot 'NoteActionPlacementTest.java')
if($LASTEXITCODE -ne 0){throw 'Note action placement compilation failed'}
& java -cp $classes cn.qingyue.pdf.NoteActionPlacementTest
if($LASTEXITCODE -ne 0){throw 'Note action placement regression failed'}

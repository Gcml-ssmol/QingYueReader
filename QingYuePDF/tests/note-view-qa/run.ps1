[CmdletBinding()]
param([string]$BuildDirectory = (Join-Path $PSScriptRoot 'out'), [string]$ProductionDirectory = '')
$ErrorActionPreference='Stop'
if (!$ProductionDirectory) { $ProductionDirectory = Join-Path $PSScriptRoot '../../app/src/main/java/cn/qingyue/pdf' }
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$production=@('NoteCanvasView.java','NoteActionMenu.java','NoteActionPlacement.java','NoteDocument.java','NoteEditorOps.java','NotePenPresets.java','NoteEditorPresentation.java','NotePageLayout.java','InkStrokeRenderer.java','InkPage.java','InkInput.java','TouchNavigation.java','TwoFingerTap.java','ZoomLimits.java') | ForEach-Object { Join-Path $ProductionDirectory $_ }
$tests=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 8 -d $classes @production @tests
if ($LASTEXITCODE -ne 0) { throw 'Production view QA compile failed' }
& java -cp $classes cn.qingyue.pdf.NoteViewQaTest
if ($LASTEXITCODE -ne 0) { throw 'Production view QA failed' }


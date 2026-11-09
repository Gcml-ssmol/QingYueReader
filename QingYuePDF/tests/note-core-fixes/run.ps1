param([Parameter(Mandatory=$true)][string]$BuildDirectory,[string]$DocumentSource='')
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
$files=@(
  (Join-Path $source 'DocumentEditLease.java'),
  $(if($DocumentSource){(Resolve-Path -LiteralPath $DocumentSource).Path}else{Join-Path $source 'NoteDocument.java'}),
  (Join-Path $source 'NoteStore.java'),
  (Join-Path $project 'tests/note-store/android/content/Context.java'),
  (Join-Path $project 'tests/note-store/android/util/AtomicFile.java'),
  (Join-Path $PSScriptRoot 'CoreLeaseAndPageTest.java')
)
& javac --release 8 -encoding UTF-8 -d $classes @files
if($LASTEXITCODE -ne 0){throw 'Core lease/page compilation failed'}
& java -cp $classes cn.qingyue.pdf.CoreLeaseAndPageTest
if($LASTEXITCODE -ne 0){throw 'Core lease/page test failed'}
$canvasClasses=Join-Path $BuildDirectory 'canvas-classes'
New-Item -ItemType Directory -Force -Path $canvasClasses | Out-Null
$viewSources=@('NoteCanvasView','NoteEditorOps','NoteTextLayout','NotePageLayout','NotePenPresets','InkStrokeRenderer','InkPage','InkInput','TouchNavigation','TwoFingerTap','ZoomLimits','NightInk','NightPageDisplay') | ForEach-Object {Join-Path $source ($_+'.java')}
$viewSources+=if($DocumentSource){(Resolve-Path -LiteralPath $DocumentSource).Path}else{Join-Path $source 'NoteDocument.java'}
$stubs=Get-ChildItem -LiteralPath (Join-Path $project 'tests/note-view-qa/src/android') -Recurse -Filter '*.java' | ForEach-Object {$_.FullName}
$canvasFiles=@($viewSources)+@($stubs)+@((Join-Path $PSScriptRoot 'android/text/Layout.java'),(Join-Path $PSScriptRoot 'android/text/TextPaint.java'),(Join-Path $PSScriptRoot 'android/text/StaticLayout.java'),(Join-Path $PSScriptRoot 'CanvasSupport.java'),(Join-Path $PSScriptRoot 'CanvasTextBoundsTest.java'))
& javac --release 8 -encoding UTF-8 -d $canvasClasses @canvasFiles
if($LASTEXITCODE -ne 0){throw 'Canvas text bounds compilation failed'}
& java -cp $canvasClasses cn.qingyue.pdf.CanvasTextBoundsTest
if($LASTEXITCODE -ne 0){throw 'Canvas text bounds test failed'}

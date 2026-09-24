param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory ('note-editor-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
$files=@((Join-Path $source 'NoteDocument.java'),(Join-Path $source 'NoteEditorOps.java'),(Join-Path $source 'ZoomLimits.java'),(Join-Path $source 'NoteStore.java'),(Join-Path $source 'BasicNoteExportBounds.java'),(Join-Path $project 'tests/note-store/android/content/Context.java'),(Join-Path $project 'tests/note-store/android/util/AtomicFile.java'),(Join-Path $PSScriptRoot 'NoteEditorTest.java'),(Join-Path $PSScriptRoot 'NoteStoreImageTest.java'))
& javac -encoding UTF-8 --release 8 -d $classes @files
if($LASTEXITCODE -ne 0){throw 'note-editor compilation failed'}
& java -cp $classes cn.qingyue.pdf.NoteEditorTest
if($LASTEXITCODE -ne 0){throw 'note-editor test failed'}
& java -cp $classes cn.qingyue.pdf.NoteStoreImageTest
if($LASTEXITCODE -ne 0){throw 'note-editor store test failed'}

param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$production=Join-Path $project 'app\src\main\java\cn\qingyue\pdf'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$run=Join-Path (Resolve-Path -LiteralPath $BuildDirectory).Path ('ocr-v2-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$sources=@('LibraryItem','LibraryFolder','LibraryIndex','LibraryStore','TrashManager','ReaderStore','ReaderDocument','InkStore','InkPage','InkHistoryRegistry','OcrSidecar' | ForEach-Object {Join-Path $production ($_+'.java')})
$sources+=@(Join-Path $PSScriptRoot 'OcrSidecarV2Test.java')
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $project 'tests\library-manager\android') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@(Join-Path $project 'tests\library-manager\cn\qingyue\pdf\NoteDocument.java'),(Join-Path $project 'tests\library-manager\cn\qingyue\pdf\NoteStore.java')
& javac --release 17 -encoding UTF-8 -d $run @sources
if($LASTEXITCODE -ne 0){throw 'OCR sidecar v2 compilation failed'}
& java -cp $run cn.qingyue.pdf.OcrSidecarV2Test
if($LASTEXITCODE -ne 0){throw 'OCR sidecar v2 test failed'}

param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$production=Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
$library=Join-Path $project 'tests/library-manager'
New-Item -ItemType Directory -Force $BuildDirectory | Out-Null
$build=(Resolve-Path $BuildDirectory).Path
$names=@('LibraryItem','LibraryFolder','LibraryIndex','LibraryStore','TrashManager',
    'ReaderStore','ReaderDocument','InkStore','InkPage','InkHistoryRegistry','NoteDocument',
    'NoteStore','OcrSidecar','GlobalSearchSource','GlobalSearchEngine',
    'GlobalSearchPdfSource','GlobalSearchLibrarySource','GlobalSearchContentProvider',
    'GlobalSearchProviderSource','RecognitionOcrFingerprint',
    'RecognitionOcrSidecarProvider','RecognitionNoteTextProvider',
    'RecognitionReaderTextProvider')
$sources=@($names | ForEach-Object {Join-Path $production "$_.java"})
$sources+=Join-Path $PSScriptRoot 'CurrentProviderIntegrationTest.java'
$sources+=Join-Path $PSScriptRoot 'android/content/Context.java'
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $library 'android') -Recurse -Filter '*.java' |
    Where-Object {$_.FullName -notlike '*android\content\Context.java'} | ForEach-Object FullName)
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $project 'tests/pdf-search-smoke/android') -Recurse -Filter '*.java' |
    Where-Object {$_.FullName -notmatch 'android[\\/]content[\\/]Context.java|android[\\/]util[\\/]AtomicFile.java|android[\\/]os[\\/]ParcelFileDescriptor.java|android[\\/]graphics[\\/]pdf[\\/]PdfRenderer.java'} | ForEach-Object FullName)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pdfbox=Join-Path $build 'pdfbox'
if(!(Test-Path $pdfbox)){[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $project 'third-party/pdfbox-android-2.0.27.0.aar'),$pdfbox)}
$classpath=(@($build,(Join-Path $pdfbox 'classes.jar'))+@(Get-ChildItem -LiteralPath (Join-Path $project 'third-party') -Filter '*.jar' | ForEach-Object FullName)) -join [System.IO.Path]::PathSeparator
& javac -encoding UTF-8 --release 17 -cp $classpath -d $build @sources
if($LASTEXITCODE -ne 0){throw 'Current provider compilation failed'}
& java -cp $classpath cn.qingyue.pdf.CurrentProviderIntegrationTest
if($LASTEXITCODE -ne 0){throw 'Current provider integration test failed'}

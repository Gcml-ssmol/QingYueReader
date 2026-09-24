param([string]$BuildDirectory = (Join-Path $env:TEMP ('qingyue-backup-reader-' + [Guid]::NewGuid().ToString('N'))))
$ErrorActionPreference='Stop'
$project=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$source=Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$names=@('LibraryItem','LibraryFolder','LibraryIndex','LibraryStore','TrashManager','BackupArchive','BackupManager','NoteDocument','NoteStore','InkPage','InkStore','InkHistoryRegistry','ReaderDocument','ReaderSourceLink','ReaderStore','OcrSidecar')
$sources=@($names | ForEach-Object { Join-Path $source ($_.ToString()+'.java') })
$sources+=@(
    (Join-Path $PSScriptRoot 'BackupReaderRoundTripTest.java'),
    (Join-Path $PSScriptRoot 'android/content/Context.java'),
    (Join-Path $PSScriptRoot 'android/content/ContentResolver.java'),
    (Join-Path $PSScriptRoot 'android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'android/net/Uri.java'),
    (Join-Path $PSScriptRoot 'android/util/AtomicFile.java'),
    (Join-Path $PSScriptRoot 'android/os/ParcelFileDescriptor.java'),
    (Join-Path $PSScriptRoot 'android/graphics/pdf/PdfRenderer.java')
)
& javac --release 17 -encoding UTF-8 -d $BuildDirectory $sources
if($LASTEXITCODE -ne 0){throw 'Backup reader regression compile failed'}
& java -cp $BuildDirectory cn.qingyue.pdf.BackupReaderRoundTripTest
if($LASTEXITCODE -ne 0){throw 'Backup reader regression failed'}

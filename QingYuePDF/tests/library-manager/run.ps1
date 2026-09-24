param([string]$BuildDirectory = (Join-Path $env:TEMP ('qingyue-library-manager-' + [Guid]::NewGuid().ToString('N'))))
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$production = Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$sources = @(
    (Join-Path $production 'LibraryItem.java'),
    (Join-Path $production 'LibraryFolder.java'),
    (Join-Path $production 'LibraryIndex.java'),
    (Join-Path $production 'LibraryStore.java'),
    (Join-Path $production 'TrashManager.java'),
    (Join-Path $production 'ReaderStore.java'),
    (Join-Path $production 'ReaderDocument.java'),
    (Join-Path $production 'InkStore.java'),
    (Join-Path $production 'InkPage.java'),
    (Join-Path $production 'InkHistoryRegistry.java'),
    (Join-Path $PSScriptRoot 'ManagerRegressionTest.java'),
    (Join-Path $PSScriptRoot 'android/content/Context.java'),
    (Join-Path $PSScriptRoot 'android/content/ContentResolver.java'),
    (Join-Path $PSScriptRoot 'android/net/Uri.java'),
    (Join-Path $PSScriptRoot 'android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'android/util/AtomicFile.java'),
    (Join-Path $PSScriptRoot 'android/os/ParcelFileDescriptor.java'),
    (Join-Path $PSScriptRoot 'android/graphics/pdf/PdfRenderer.java'),
    (Join-Path $PSScriptRoot 'cn/qingyue/pdf/NoteDocument.java'),
    (Join-Path $PSScriptRoot 'cn/qingyue/pdf/NoteStore.java')
)
& javac -version
if ($LASTEXITCODE -ne 0) { throw 'JDK not available.' }
& javac --release 17 -encoding UTF-8 -d $BuildDirectory $sources
if ($LASTEXITCODE -ne 0) { throw 'Library manager regression compilation failed.' }
& java -cp $BuildDirectory cn.qingyue.pdf.ManagerRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Library manager regression test failed.' }

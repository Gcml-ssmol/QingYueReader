param([string]$BuildDirectory = (Join-Path $env:TEMP ('qingyue-note-tests-' + [Guid]::NewGuid().ToString('N'))))
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$source = Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
& javac --release 8 -encoding UTF-8 -d $BuildDirectory (Join-Path $source 'NoteDocument.java') (Join-Path $source 'NoteStore.java') (Join-Path $project 'tests/NoteDocumentTest.java') (Join-Path $PSScriptRoot 'android/content/Context.java') (Join-Path $PSScriptRoot 'android/util/AtomicFile.java') (Join-Path $PSScriptRoot 'NoteStoreTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Note tests failed to compile.' }
& java -cp $BuildDirectory cn.qingyue.pdf.NoteDocumentTest
if ($LASTEXITCODE -ne 0) { throw 'NoteDocumentTest failed.' }
& java -cp $BuildDirectory cn.qingyue.pdf.NoteStoreTest
if ($LASTEXITCODE -ne 0) { throw 'NoteStoreTest failed.' }

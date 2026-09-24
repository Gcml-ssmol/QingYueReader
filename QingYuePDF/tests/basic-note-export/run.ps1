param([string]$BuildDirectory = (Join-Path $PSScriptRoot '../../../work/basic-note-export'))
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$classes = Join-Path $BuildDirectory ('classes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
    javac -encoding UTF-8 --release 8 -d $classes `
        (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/NoteDocument.java') `
        (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/BasicNoteExportBounds.java') `
        (Join-Path $PSScriptRoot 'BasicNoteExportBoundsTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    java -cp $classes cn.qingyue.pdf.BasicNoteExportBoundsTest
    if ($LASTEXITCODE -ne 0) { throw 'Bounds test failed' }
Write-Output 'PASS: fixed paper, empty infinite canvas, negative coordinates, pixel caps and excessive range rejection'

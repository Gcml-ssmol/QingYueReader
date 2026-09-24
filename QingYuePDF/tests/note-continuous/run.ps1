param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $BuildDirectory ('note-continuous-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/NoteDocument.java'),
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/NotePageLayout.java'),
    (Join-Path $PSScriptRoot 'NoteContinuousTest.java')
)
& javac -encoding UTF-8 --release 8 -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'note-continuous compilation failed' }
& java -cp $output cn.qingyue.pdf.NoteContinuousTest
if ($LASTEXITCODE -ne 0) { throw 'note-continuous test failed' }

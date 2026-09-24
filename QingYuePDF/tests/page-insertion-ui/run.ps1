param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run=Join-Path $BuildDirectory ('page-insertion-ui-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $run | Out-Null
$source=Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
$files=@((Join-Path $PSScriptRoot 'PageInsertionUiTest.java'),(Join-Path $source 'PageInsertionPosition.java'),(Join-Path $source 'ThumbnailBinding.java'),(Join-Path $source 'ReaderSidebarLayout.java'))
& javac -encoding UTF-8 --release 8 -d $run @files
if($LASTEXITCODE -ne 0){throw 'Insertion UI compilation failed'}
& java -cp $run cn.qingyue.pdf.PageInsertionUiTest
if($LASTEXITCODE -ne 0){throw 'Insertion UI tests failed'}

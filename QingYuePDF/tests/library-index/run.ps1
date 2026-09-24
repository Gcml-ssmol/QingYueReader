param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../../work/library-index-test'))
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory,(Join-Path $BuildDirectory 'classes') | Out-Null
$sources=@(
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/LibraryItem.java'),
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/LibraryFolder.java'),
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/LibraryIndex.java'),
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/LibraryQuery.java'),
    (Join-Path $PSScriptRoot 'LibraryIndexTest.java')
)
& javac -encoding UTF-8 --release 8 -d (Join-Path $BuildDirectory 'classes') $sources
if($LASTEXITCODE -ne 0){throw 'Library index test compilation failed'}
& java -classpath (Join-Path $BuildDirectory 'classes') cn.qingyue.pdf.LibraryIndexTest
if($LASTEXITCODE -ne 0){throw 'Library index test failed'}

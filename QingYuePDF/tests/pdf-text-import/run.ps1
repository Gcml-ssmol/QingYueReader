param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../../work/pdf-text-import-tests'))
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
& javac --release 8 -encoding UTF-8 -d $BuildDirectory (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/PdfTextImport.java') (Join-Path $PSScriptRoot 'PdfTextImportTest.java')
if($LASTEXITCODE -ne 0){throw 'TXT import test compilation failed'}
& java -cp $BuildDirectory PdfTextImportTest
if($LASTEXITCODE -ne 0){throw 'TXT import test failed'}

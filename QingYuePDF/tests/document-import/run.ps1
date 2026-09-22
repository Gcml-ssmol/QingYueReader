param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../../../../work/document-import-test'))
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory,(Join-Path $BuildDirectory 'classes'),(Join-Path $BuildDirectory 'fixtures') | Out-Null
$classpath=(@(Get-ChildItem -LiteralPath (Join-Path $project 'third-party') -Filter '*.jar' | ForEach-Object FullName)+(Join-Path $BuildDirectory 'classes')) -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 --release 8 -classpath $classpath -d (Join-Path $BuildDirectory 'classes') (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/DocumentText.java') (Join-Path $PSScriptRoot 'DocumentImportTest.java')
if($LASTEXITCODE -ne 0){throw 'Document importer test compilation failed'}
& java -Xmx256m -classpath $classpath cn.qingyue.pdf.DocumentImportTest (Join-Path $PSScriptRoot 'fixtures') (Join-Path $BuildDirectory 'fixtures')
if($LASTEXITCODE -ne 0){throw 'Document importer test failed'}


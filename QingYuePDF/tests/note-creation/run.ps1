param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 --release 8 -d $classes (Join-Path $PSScriptRoot 'stubs/android/content/SharedPreferences.java') (Join-Path $projectRoot 'app/src/main/java/cn/qingyue/pdf/NoteCreationChoices.java') (Join-Path $PSScriptRoot 'NoteCreationChoicesTest.java')
if($LASTEXITCODE -ne 0){throw 'Note creation choices compilation failed'}
& java -cp $classes cn.qingyue.pdf.NoteCreationChoicesTest
if($LASTEXITCODE -ne 0){throw 'Note creation choices regression failed'}

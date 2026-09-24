param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$source=Join-Path $PSScriptRoot '../../app/src/main/java/cn/qingyue/pdf'
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$files=@('InkInput.java','InkPage.java','NoteDocument.java','TouchNavigation.java') | ForEach-Object {Join-Path $source $_}
$files+=Join-Path $PSScriptRoot 'InteractionTest.java'
& javac -encoding UTF-8 --release 8 -d $classes @files
if($LASTEXITCODE -ne 0){throw 'Interaction compilation failed'}
& java '-Djava.awt.headless=true' -cp $classes cn.qingyue.pdf.InteractionTest
if($LASTEXITCODE -ne 0){throw 'Interaction regression failed'}

param([string]$BuildDirectory = (Join-Path $PSScriptRoot '../../work/backup-archive-test'))
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$source = Join-Path $project 'app/src/main/java/cn/qingyue/pdf/BackupArchive.java'
$test = Join-Path $PSScriptRoot 'BackupArchiveTest.java'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
& javac --release 8 -encoding UTF-8 -d $BuildDirectory $source $test
if ($LASTEXITCODE -ne 0) { throw 'BackupArchiveTest failed to compile.' }
& java -cp $BuildDirectory cn.qingyue.pdf.BackupArchiveTest
if ($LASTEXITCODE -ne 0) { throw 'BackupArchiveTest failed.' }

param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../work/backup-session-test'))
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$classes=Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $BuildDirectory,$classes | Out-Null
$sources=@(
    (Join-Path $project 'app/src/main/java/cn/qingyue/pdf/BackupCoordinator.java'),
    (Join-Path $PSScriptRoot 'android/os/Looper.java'),
    (Join-Path $PSScriptRoot 'android/os/Handler.java'),
    (Join-Path $PSScriptRoot 'cn/qingyue/pdf/BackupManager.java'),
    (Join-Path $PSScriptRoot 'BackupCoordinatorTest.java')
)
& javac -encoding UTF-8 --release 8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Backup coordinator test compilation failed'}
& java -classpath $classes cn.qingyue.pdf.BackupCoordinatorTest
if($LASTEXITCODE -ne 0){throw 'Backup coordinator test failed'}

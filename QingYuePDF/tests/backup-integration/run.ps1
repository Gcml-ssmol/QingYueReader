param([string]$BuildDirectory = (Join-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) 'work/backup-integration-tests'))
& (Join-Path $PSScriptRoot '../library-manager/run-backup-reader.ps1') -BuildDirectory $BuildDirectory
if($LASTEXITCODE -ne 0){throw 'Backup integration regression failed'}

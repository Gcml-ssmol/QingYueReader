$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$abaqusExe = (Get-Command abaqus.bat -ErrorAction Stop).Source
$solverDir = Join-Path $env:TEMP ('codex_pliers_' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $solverDir | Out-Null
$previousJob = $env:PLIERS_JOB
Copy-Item -LiteralPath (Join-Path $projectDir 'extract_results.py'),(Join-Path $projectDir 'export_clouds.py') -Destination $solverDir
Push-Location $solverDir
try {
    foreach ($jobLabel in @('Pliers_Main', 'Pliers_Fine')) {
        Copy-Item -LiteralPath (Join-Path $projectDir ($jobLabel + '.inp')) -Destination $solverDir
        $env:PLIERS_JOB = $jobLabel
        & $abaqusExe "job=$jobLabel" "input=$jobLabel.inp" cpus=2 mp_mode=threads interactive ask_delete=OFF
        if ($LASTEXITCODE -ne 0) { throw "Abaqus failed: $jobLabel" }
        & $abaqusExe python extract_results.py
        if ($LASTEXITCODE -ne 0) { throw "ODB extraction failed: $jobLabel" }
        & $abaqusExe cae noGUI=export_clouds.py
        if ($LASTEXITCODE -ne 0) { throw "Plot export failed: $jobLabel" }
        foreach ($suffix in @('.odb', '.dat', '.msg', '.sta', '_results.json', '_mises_100N.png', '_displacement_100N.png', '_model_iso.png')) {
            Copy-Item -LiteralPath (Join-Path $solverDir ($jobLabel + $suffix)) -Destination $projectDir
        }
    }
} finally {
    Pop-Location
    $env:PLIERS_JOB = $previousJob
}
Write-Output "Analysis outputs copied to $projectDir"
Write-Output "Working files retained in $solverDir"

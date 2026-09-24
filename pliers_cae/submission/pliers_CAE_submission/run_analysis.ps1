param(
    [string]$AbaqusCommand = 'abaqus.bat',
    [switch]$Rebuild,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$abaqusExe = (Get-Command $AbaqusCommand -ErrorAction Stop).Source
if (-not $abaqusExe) { throw 'Abaqus command was not found.' }
$required = @('build_pliers.py', 'extract_results.py', 'export_clouds.py', 'Pliers_Main.inp', 'Pliers_Fine.inp')
foreach ($item in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectDir $item))) { throw "Missing file: $item" }
}
Write-Output "Abaqus: $abaqusExe"
if ($CheckOnly) { Write-Output 'Launcher preflight passed.'; exit 0 }
# Use a fresh ASCII working directory; retain all files and earlier results.
if ($env:TEMP -match '[^\x00-\x7F]') {
    throw 'TEMP contains non-ASCII characters. Set TEMP to an existing writable English-path folder for this run.'
}
$runTag = 'pliers_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [guid]::NewGuid().ToString('N').Substring(0,8)
$solverDir = Join-Path $env:TEMP $runTag
$resultDir = Join-Path (Join-Path $projectDir 'rerun_results') $runTag
New-Item -ItemType Directory -Path $solverDir,$resultDir -Force | Out-Null
foreach ($item in $required) { Copy-Item -LiteralPath (Join-Path $projectDir $item) -Destination $solverDir }
$savedJob = $env:PLIERS_JOB
$savedMesh = $env:PLIERS_MESH
$savedFinal = $env:PLIERS_FINAL_ONLY
Push-Location $solverDir
try {
    foreach ($jobLabel in @('Pliers_Main','Pliers_Fine')) {
        $env:PLIERS_JOB = $jobLabel
        if ($jobLabel -eq 'Pliers_Main') { $env:PLIERS_MESH='2.5'; $env:PLIERS_FINAL_ONLY='0' }
        else { $env:PLIERS_MESH='2.0'; $env:PLIERS_FINAL_ONLY='1' }
        if ($Rebuild) {
            & $abaqusExe cae noGUI=build_pliers.py
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath ($jobLabel+'.cae'))) { throw "Model generation failed: $jobLabel" }
        }
        & $abaqusExe "job=$jobLabel" "input=$jobLabel.inp" cpus=2 mp_mode=threads interactive ask_delete=OFF
        if ($LASTEXITCODE -ne 0) { throw "Solver failed: $jobLabel" }
        $statusFile = $jobLabel + '.sta'
        if (-not (Test-Path -LiteralPath $statusFile)) { throw "Missing solver status: $jobLabel" }
        if ((Get-Content -LiteralPath $statusFile -Raw) -notmatch 'THE ANALYSIS HAS COMPLETED SUCCESSFULLY') { throw "Analysis did not complete: $jobLabel" }
        & $abaqusExe python extract_results.py
        if ($LASTEXITCODE -ne 0) { throw "Result extraction failed: $jobLabel" }
        & $abaqusExe cae noGUI=export_clouds.py
        if ($LASTEXITCODE -ne 0) { throw "Plot export failed: $jobLabel" }
        foreach ($suffix in @('.cae','.inp','.odb','.sta','.dat','.msg','_mesh.json','_results.json','_mises_100N.png','_displacement_100N.png','_model_iso.png')) {
            $sourceFile=Join-Path $solverDir ($jobLabel+$suffix)
            if (Test-Path -LiteralPath $sourceFile) { Copy-Item -LiteralPath $sourceFile -Destination $resultDir }
        }
        Get-ChildItem -LiteralPath $solverDir -Filter ($jobLabel+'_cpress_*.png') | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $resultDir
        }
    }
} finally {
    Pop-Location
    $env:PLIERS_JOB=$savedJob
    $env:PLIERS_MESH=$savedMesh
    $env:PLIERS_FINAL_ONLY=$savedFinal
}
Write-Output "Finished. New results: $resultDir"
Write-Output "Solver working files retained: $solverDir"

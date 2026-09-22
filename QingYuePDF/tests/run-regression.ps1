[CmdletBinding()]
param(
    [string]$BuildDirectory = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'work/tests'),
    [string[]]$AdditionalSuiteAllowList = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$testsRoot = $PSScriptRoot
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $testsRoot '..')).Path
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/cn/qingyue/pdf'

New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$buildRoot = (Resolve-Path -LiteralPath $BuildDirectory).Path
$runId = (Get-Date -Format 'yyyyMMdd-HHmmssfff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $buildRoot $runId
New-Item -ItemType Directory -Path $runRoot | Out-Null

$results = @()

function Add-Result {
    param(
        [string]$Name,
        [ValidateSet('PASS', 'FAIL', 'SKIP')][string]$Status,
        [double]$Seconds,
        [string]$Detail
    )
    $script:results += [pscustomobject]@{
        Name = $Name
        Status = $Status
        Seconds = [Math]::Round($Seconds, 2)
        Detail = $Detail
    }
}

function Invoke-JavaCase {
    param(
        [string]$Name,
        [string]$ClassName,
        [string]$ClassesDirectory
    )
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "[RUN ] pure-java/$Name"
    try {
        & java -cp $ClassesDirectory $ClassName
        if ($LASTEXITCODE -ne 0) {
            throw "java exited with code $LASTEXITCODE"
        }
        $watch.Stop()
        Add-Result -Name "pure-java/$Name" -Status PASS -Seconds $watch.Elapsed.TotalSeconds -Detail $ClassName
        Write-Host "[PASS] pure-java/$Name"
    }
    catch {
        $watch.Stop()
        Add-Result -Name "pure-java/$Name" -Status FAIL -Seconds $watch.Elapsed.TotalSeconds -Detail $_.Exception.Message
        Write-Host "[FAIL] pure-java/$Name - $($_.Exception.Message)"
    }
}

function Invoke-ScriptSuite {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [string]$SuiteBuildDirectory,
        [string]$PowerShellExecutable
    )
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "[RUN ] script/$Name"
    try {
        New-Item -ItemType Directory -Force -Path $SuiteBuildDirectory | Out-Null
        & $PowerShellExecutable -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $ScriptPath -BuildDirectory $SuiteBuildDirectory
        if ($LASTEXITCODE -ne 0) {
            throw "suite process exited with code $LASTEXITCODE"
        }
        $watch.Stop()
        Add-Result -Name "script/$Name" -Status PASS -Seconds $watch.Elapsed.TotalSeconds -Detail $SuiteBuildDirectory
        Write-Host "[PASS] script/$Name"
    }
    catch {
        $watch.Stop()
        Add-Result -Name "script/$Name" -Status FAIL -Seconds $watch.Elapsed.TotalSeconds -Detail $_.Exception.Message
        Write-Host "[FAIL] script/$Name - $($_.Exception.Message)"
    }
}

Write-Host "QingYuePDF regression run"
Write-Host "Project:   $projectRoot"
Write-Host "Artifacts: $runRoot"

$testCases = @(
    [pscustomobject]@{ Name = 'InkPageTest'; Class = 'cn.qingyue.pdf.InkPageTest' },
    [pscustomobject]@{ Name = 'PageMathTest'; Class = 'cn.qingyue.pdf.PageMathTest' },
    [pscustomobject]@{ Name = 'PageLayoutTest'; Class = 'cn.qingyue.pdf.PageLayoutTest' },
    [pscustomobject]@{ Name = 'ReaderNavigationTest'; Class = 'cn.qingyue.pdf.ReaderNavigationTest' },
    [pscustomobject]@{ Name = 'ToolSizeTest'; Class = 'cn.qingyue.pdf.ToolSizeTest' },
    [pscustomobject]@{ Name = 'ColorPickInputTest'; Class = 'cn.qingyue.pdf.ColorPickInputTest' },
    [pscustomobject]@{ Name = 'PopupPlacementTest'; Class = 'cn.qingyue.pdf.PopupPlacementTest' },
    [pscustomobject]@{ Name = 'TwoFingerTapTest'; Class = 'cn.qingyue.pdf.TwoFingerTapTest' },
    [pscustomobject]@{ Name = 'HorizontalPagingTest'; Class = 'cn.qingyue.pdf.HorizontalPagingTest' },
    [pscustomobject]@{ Name = 'SearchTextTest'; Class = 'cn.qingyue.pdf.SearchTextTest' },
    [pscustomobject]@{ Name = 'NoteDocumentTest'; Class = 'cn.qingyue.pdf.NoteDocumentTest' }
)

$productionFiles = @(
    'InkPage.java',
    'InkInput.java',
    'PageMath.java',
    'PageLayout.java',
    'ReaderNavigation.java',
    'ToolSize.java',
    'PopupPlacement.java',
    'TwoFingerTap.java',
    'HorizontalPaging.java',
    'SearchText.java',
    'NoteDocument.java'
) | ForEach-Object { Join-Path $sourceRoot $_ }
$testFiles = @($testCases | ForEach-Object { Join-Path $testsRoot ($_.Name + '.java') })
$compileFiles = @($productionFiles) + $testFiles
$pureJavaRoot = Join-Path $runRoot 'pure-java'
$classesDirectory = Join-Path $pureJavaRoot 'classes'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null

$compileWatch = [Diagnostics.Stopwatch]::StartNew()
$compiled = $false
Write-Host '[RUN ] pure-java/compile'
try {
    & javac -encoding UTF-8 --release 8 -d $classesDirectory @compileFiles
    if ($LASTEXITCODE -ne 0) {
        throw "javac exited with code $LASTEXITCODE"
    }
    $compileWatch.Stop()
    $compiled = $true
    Add-Result -Name 'pure-java/compile' -Status PASS -Seconds $compileWatch.Elapsed.TotalSeconds -Detail "$($testCases.Count) tests"
    Write-Host '[PASS] pure-java/compile'
}
catch {
    $compileWatch.Stop()
    Add-Result -Name 'pure-java/compile' -Status FAIL -Seconds $compileWatch.Elapsed.TotalSeconds -Detail $_.Exception.Message
    Write-Host "[FAIL] pure-java/compile - $($_.Exception.Message)"
}

if ($compiled) {
    foreach ($testCase in $testCases) {
        Invoke-JavaCase -Name $testCase.Name -ClassName $testCase.Class -ClassesDirectory $classesDirectory
    }
}
else {
    foreach ($testCase in $testCases) {
        Add-Result -Name "pure-java/$($testCase.Name)" -Status SKIP -Seconds 0 -Detail 'compile failed'
        Write-Host "[SKIP] pure-java/$($testCase.Name) - compile failed"
    }
}

$builtInSuites = @('note-store', 'document-import', 'pdf-search-smoke')
$allowedSuites = @($builtInSuites)
foreach ($suiteName in $AdditionalSuiteAllowList) {
    if ([string]::IsNullOrWhiteSpace($suiteName) -or
        $suiteName -ne [IO.Path]::GetFileName($suiteName) -or
        $suiteName -eq '.' -or $suiteName -eq '..') {
        throw "Additional suite names must be direct child directory names: '$suiteName'"
    }
    if ($allowedSuites -notcontains $suiteName) {
        $allowedSuites += $suiteName
    }
}

$discoveredSuites = @(
    Get-ChildItem -LiteralPath $testsRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'run.ps1') -PathType Leaf } |
        Sort-Object Name
)

foreach ($suite in $discoveredSuites) {
    if ($allowedSuites -notcontains $suite.Name) {
        Add-Result -Name "script/$($suite.Name)" -Status SKIP -Seconds 0 -Detail 'discovered; not allow-listed'
        Write-Host "[SKIP] script/$($suite.Name) - discovered but not allow-listed; use -AdditionalSuiteAllowList '$($suite.Name)'"
    }
}

$powerShellExecutable = (Get-Process -Id $PID).Path
foreach ($suiteName in $allowedSuites) {
    $suite = @($discoveredSuites | Where-Object { $_.Name -ieq $suiteName } | Select-Object -First 1)
    if ($suite.Count -eq 0) {
        Add-Result -Name "script/$suiteName" -Status FAIL -Seconds 0 -Detail 'allow-listed run.ps1 was not found'
        Write-Host "[FAIL] script/$suiteName - allow-listed run.ps1 was not found"
        continue
    }
    $suiteBuildDirectory = Join-Path (Join-Path $runRoot 'script-suites') $suite[0].Name
    Invoke-ScriptSuite -Name $suite[0].Name -ScriptPath (Join-Path $suite[0].FullName 'run.ps1') -SuiteBuildDirectory $suiteBuildDirectory -PowerShellExecutable $powerShellExecutable
}

Write-Host ''
Write-Host 'Regression summary'
Write-Host ($results | Format-Table Name, Status, Seconds, Detail -AutoSize | Out-String)

$passed = @($results | Where-Object { $_.Status -eq 'PASS' }).Count
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' }).Count
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' }).Count
Write-Host "PASS=$passed FAIL=$failed SKIP=$skipped"
Write-Host "Artifacts: $runRoot"

if ($failed -gt 0) {
    exit 1
}
exit 0

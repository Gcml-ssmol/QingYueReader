param(
    [Parameter(Mandatory=$true)][string]$BuildDirectory,
    [Parameter(Mandatory=$true)][string]$SdkRoot
)
$ErrorActionPreference = 'Stop'
$build = (Resolve-Path -LiteralPath $BuildDirectory).Path
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
$dexdump = Join-Path $sdk 'build-tools\35.0.0\dexdump.exe'
if (-not (Test-Path -LiteralPath $dexdump -PathType Leaf)) { throw "dexdump missing: $dexdump" }
$dexFiles = @(Get-ChildItem -LiteralPath (Join-Path $build 'dex') -Filter '*.dex' -File)
if ($dexFiles.Count -eq 0) { throw 'No built DEX files found.' }

# Inspect executable DEX, after D8 has rewritten Java platform library calls.
$calls = [System.Collections.Generic.List[string]]::new()
$methodCounts = @{}
$librarySetMethod = $false
$libraryFound = $false
foreach ($dex in $dexFiles) {
    $class = ''
    $method = ''
    $lastName = ''
    Push-Location -LiteralPath $build
    try {
    & $dexdump -d (Join-Path 'dex' $dex.Name) | ForEach-Object {
        $line = $_
        if ($line -match "^\s*Class descriptor\s*: '([^']+)'\s*$") {
            $class = $Matches[1]
            $method = ''
            $lastName = ''
            if ($class -eq 'Lj$/util/concurrent/ConcurrentHashMap;') { $libraryFound = $true }
        } elseif ($line -match "^\s*name\s*: '([^']+)'\s*$") {
            $lastName = $Matches[1]
        } elseif ($class -eq 'Lj$/util/concurrent/ConcurrentHashMap;' -and
                  $lastName -eq 'keySet' -and
                  $line -match "^\s*type\s*: '\(\)Ljava/util/Set;'\s*$") {
            $librarySetMethod = $true
        }
        if ($class -eq 'Lcn/qingyue/pdf/InkStore;' -and
            $line -match 'cn\.qingyue\.pdf\.InkStore\.(annotatedPages|invalidateDocument):') {
            $method = $Matches[1]
            if (-not $methodCounts.ContainsKey($method)) { $methodCounts[$method] = 0 }
        }
        if ($class -eq 'Lcn/qingyue/pdf/InkStore;' -and $method -ne '' -and
            $line -match '\|[^\r\n]*invoke-(?:virtual|interface)[^\r\n]*\.keySet:\(\)') {
            $calls.Add("$($dex.Name):$method : $line")
            $methodCounts[$method]++
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "dexdump failed for $($dex.FullName)" }
    } finally { Pop-Location }
}
if (-not $libraryFound -or -not $librarySetMethod) {
    throw 'Packaged j$ ConcurrentHashMap.keySet():java.util.Set implementation is missing.'
}
if ($methodCounts['annotatedPages'] -ne 1 -or $methodCounts['invalidateDocument'] -ne 2 -or $calls.Count -ne 3) {
    throw "Expected 1 annotatedPages and 2 invalidateDocument keySet invocations; found $($methodCounts['annotatedPages']) and $($methodCounts['invalidateDocument'])."
}
foreach ($call in $calls) {
    if ($call -match 'KeySetView;') { throw "Incompatible covariant keySet linkage: $call" }
    if ($call -notmatch 'keySet:\(\)Ljava/util/Set;') { throw "Unexpected keySet return type: $call" }
    if ($call -notmatch '(?:Ljava/util/Map;|Ljava/util/concurrent/ConcurrentMap;|Lj\$/util/concurrent/ConcurrentMap;|Lj\$/util/concurrent/ConcurrentHashMap;)\.keySet:') {
        throw "Unexpected keySet owner: $call"
    }
}
Write-Output 'Desugar linkage passed: InkStore has three keySet():Set DEX calls and the packaged implementation provides keySet():Set.'

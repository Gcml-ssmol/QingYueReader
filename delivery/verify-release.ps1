[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$SourceArchive,
    [string]$SourceDirectory,
    [string]$ExpectedVersion = '1.8.0',
    [int]$ExpectedVersionCode = 9,
    [string]$ExpectedCertificateSha256 = 'be5604ac60eca81271ec761830359cae7bb8fb42a2dfa4a00325f8dc8ae3323f',
    [string]$ReportPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
$lines = [Collections.Generic.List[string]]::new()
function Record([string]$text) { $lines.Add($text); Write-Output $text }
Record ('Artifact verification at ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
Record ('APK: ' + $apkPath)
Push-Location -LiteralPath (Split-Path $apkPath -Parent)
try {
    # Relative APK arguments avoid the Windows SDK tools' Unicode directory limitation.
    $apkName = Split-Path $apkPath -Leaf
    $signature = @(& (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apkName 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ('APK signature verification failed: ' + ($signature -join "`n")) }
    $signatureText = $signature -join "`n"
    $certificateMatch = [regex]::Match($signatureText, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $certificateMatch.Success -or $certificateMatch.Groups[1].Value -ine $ExpectedCertificateSha256) {
        throw 'APK certificate differs from the existing QingYue installation certificate'
    }
    Record ('PASS signing certificate: ' + $certificateMatch.Groups[1].Value.ToLowerInvariant())
    $badging = @(& (Join-Path $buildTools 'aapt2.exe') dump badging $apkName 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ('APK metadata read failed: ' + ($badging -join "`n")) }
    $metadata = $badging -join "`n"
    $expectedPackage = "package: name='cn.qingyue.pdf' versionCode='$ExpectedVersionCode' versionName='" + [regex]::Escape($ExpectedVersion) + "'"
    if ($metadata -notmatch $expectedPackage) { throw 'Unexpected APK package/version identity' }
    if ($metadata -notmatch "sdkVersion:'23'" -or $metadata -notmatch "targetSdkVersion:'35'") {
        throw 'Unexpected Android API requirements'
    }
    if ($metadata -match 'application-debuggable') { throw 'Final APK must not be debuggable' }
    Record "PASS package cn.qingyue.pdf; version $ExpectedVersion ($ExpectedVersionCode); minSdk 23; targetSdk 35"
    $alignment = @(& (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $apkName 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ('APK ZIP alignment failed: ' + ($alignment -join "`n")) }
    Record 'PASS APK ZIP alignment (-P 16)'
} finally { Pop-Location }
Record ('APK SHA256: ' + (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant())
Record ('APK bytes: ' + (Get-Item -LiteralPath $apkPath).Length)

if ($SourceArchive) {
    $archivePath = (Resolve-Path -LiteralPath $SourceArchive).Path
    if (-not $SourceDirectory) { throw 'SourceDirectory is required to compare every archived file with its delivered source' }
    $sourcePath = (Resolve-Path -LiteralPath $SourceDirectory).Path
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $fileCount = 0
        $buffer = New-Object byte[] 65536
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\','/')
            if (-not $names.Add($name)) { throw "Duplicate source archive entry: $name" }
            if ($name.StartsWith('/') -or $name.Contains(':') -or $name -match '(^|/)\.\.(/|$)' -or -not $name.StartsWith('QingYuePDF/')) {
                throw "Unsafe or unexpected source archive path: $name"
            }
            if ($name -match '(?i)(^|/)(\.git|work|build-local|build-test|\.gradle)(/|$)' -or
                $name -match '(?i)\.(keystore|jks|apk|idsig)$' -or $name -match '(?i)(^|/)local\.properties$') {
                throw "Build output or private material in source archive: $name"
            }
            if ($entry.Name.Length -eq 0) { continue }
            $fileCount++
            $sourceFile = Join-Path $sourcePath $name.Substring('QingYuePDF/'.Length)
            if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) { throw "Source file missing beside archive: $name" }
            $stream = $entry.Open()
            $digest = [Security.Cryptography.SHA256]::Create()
            try {
                [long]$readBytes = 0
                while (($count = $stream.Read($buffer,0,$buffer.Length)) -gt 0) {
                    $readBytes += $count
                    $digest.TransformBlock($buffer,0,$count,$buffer,0) | Out-Null
                }
                $digest.TransformFinalBlock([byte[]]@(),0,0) | Out-Null
                if ($readBytes -ne $entry.Length) { throw "Truncated source archive entry: $name" }
                $entryHash = [BitConverter]::ToString($digest.Hash).Replace('-','')
                if ($entryHash -ine (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash) {
                    throw "Source archive bytes differ from delivered source: $name"
                }
            } finally { $stream.Dispose(); $digest.Dispose() }
        }
        foreach ($required in @('QingYuePDF/README.md','QingYuePDF/build-apk.ps1',
                'QingYuePDF/app/src/main/AndroidManifest.xml','QingYuePDF/tests/run-regression.ps1',
                'QingYuePDF/third-party/dependencies.json')) {
            if (-not $names.Contains($required)) { throw "Source archive missing $required" }
        }
        $manifestEntry = $archive.GetEntry('QingYuePDF/app/src/main/AndroidManifest.xml')
        $reader = [IO.StreamReader]::new($manifestEntry.Open(), [Text.Encoding]::UTF8, $true)
        try { [xml]$sourceManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $androidNamespace = 'http://schemas.android.com/apk/res/android'
        if ($sourceManifest.manifest.GetAttribute('versionName',$androidNamespace) -ne $ExpectedVersion -or
            $sourceManifest.manifest.GetAttribute('versionCode',$androidNamespace) -ne [string]$ExpectedVersionCode) {
            throw 'Source manifest version does not match the APK'
        }
        $sourceFiles = @(Get-ChildItem -LiteralPath $sourcePath -Recurse -File)
        if ($sourceFiles.Count -ne $fileCount) { throw 'Source archive omits files from the delivered source directory' }
        Record "PASS source archive paths, required files, version, and per-file SHA256 readback ($fileCount files)"
    } finally { $archive.Dispose() }
    Record ('Source ZIP: ' + $archivePath)
    Record ('Source ZIP SHA256: ' + (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant())
}
Record 'Android device installation, UI, touch input, file picker and exported rendering: NOT TESTED'
if ($ReportPath) {
    $reportFullPath = [IO.Path]::GetFullPath($ReportPath)
    [IO.Directory]::CreateDirectory((Split-Path $reportFullPath -Parent)) | Out-Null
    [IO.File]::WriteAllLines($reportFullPath, $lines, [Text.UTF8Encoding]::new($false))
}

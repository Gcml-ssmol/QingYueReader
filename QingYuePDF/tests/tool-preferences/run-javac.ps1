param([Parameter(Mandatory=$true)][string]$SdkRoot)
$ErrorActionPreference = 'Stop'
$project = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$thirdParty = Join-Path $project 'third-party'
$temporary = Join-Path $env:TEMP ('qingyue-javac-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $temporary 'classes'
$pdfbox = Join-Path $temporary 'pdfbox'
New-Item -ItemType Directory -Path $classes, $pdfbox -Force | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $thirdParty 'pdfbox-android-2.0.27.0.aar'), $pdfbox)
    $libraries = @($androidJar, (Join-Path $pdfbox 'classes.jar')) + @(Get-ChildItem $thirdParty -Filter '*.jar' | ForEach-Object FullName)
    $classpath = $libraries -join [System.IO.Path]::PathSeparator
    $sources = @(Get-ChildItem (Join-Path $project 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
    & javac -encoding UTF-8 --release 8 -classpath $classpath -d $classes @sources
    exit $LASTEXITCODE
} finally {
    $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
    $resolvedTempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ((Split-Path $resolvedTemporary -Parent) -ne $resolvedTempRoot -or (Split-Path $resolvedTemporary -Leaf) -notlike 'qingyue-javac-*' -or (Get-Item -LiteralPath $resolvedTemporary).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing to remove unexpected test directory' }
    Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
}

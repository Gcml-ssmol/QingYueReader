param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$BuildDirectory = (Join-Path $PSScriptRoot 'build-local'),
    [string]$OutputApk = (Join-Path $PSScriptRoot 'qingyue-pdf-1.14.0.apk')
)
$ErrorActionPreference='Stop'
$SdkRoot=(Resolve-Path -LiteralPath $SdkRoot).Path
$bt=Join-Path $SdkRoot 'build-tools\35.0.0'
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$src=Join-Path $PSScriptRoot 'app\src\main'
$dependencies=Join-Path $PSScriptRoot 'third-party'
New-Item -ItemType Directory -Force -Path $BuildDirectory,(Join-Path $BuildDirectory 'classes'),(Join-Path $BuildDirectory 'dex'),(Join-Path $BuildDirectory 'generated'),(Join-Path $BuildDirectory 'libraries'),(Split-Path -Parent $OutputApk) | Out-Null
$BuildDirectory=(Resolve-Path -LiteralPath $BuildDirectory).Path
# Delete only verified generated child directories; retain the existing signing key.
foreach($child in @('classes','dex','generated','libraries')) {
    $target=(Resolve-Path -LiteralPath (Join-Path $BuildDirectory $child)).Path
    if((Split-Path -Parent $target) -ne $BuildDirectory) { throw 'Generated directory is outside the build directory' }
    if((Get-Item -LiteralPath $target).Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'Refusing to clear a linked generated directory' }
    Remove-Item -LiteralPath $target -Recurse -Force
    New-Item -ItemType Directory -Path $target | Out-Null
}
function Check([string]$step){ if($LASTEXITCODE -ne 0){throw "$step failed with exit code $LASTEXITCODE"} }
Add-Type -AssemblyName System.IO.Compression.FileSystem
# The checked-in AAR includes PDFBox's glyph lists and Chinese/Japanese/Korean CMaps.
# Verify pinned dependency bytes before extracting, so manual and Gradle builds stay reproducible.
$dependencyManifest=Get-Content -LiteralPath (Join-Path $dependencies 'dependencies.json') -Raw | ConvertFrom-Json
foreach($dependency in $dependencyManifest) {
    $library=Join-Path $dependencies $dependency.file
    if((Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash -ne $dependency.sha256) { throw "Dependency checksum mismatch: $($dependency.file)" }
}
$pdfbox=Join-Path $BuildDirectory 'libraries\pdfbox'
[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $dependencies 'pdfbox-android-2.0.27.0.aar'),$pdfbox)
$ocrDependencies=Join-Path $dependencies 'ocr'
foreach($line in Get-Content -LiteralPath (Join-Path $ocrDependencies 'SHA256SUMS')) {
    if($line -notmatch '^([a-fA-F0-9]{64})\s+(.+)$') { throw 'Invalid OCR checksum manifest' }
    $expected=$Matches[1]; $relative=$Matches[2]
    if((Get-FileHash -LiteralPath (Join-Path $ocrDependencies $relative) -Algorithm SHA256).Hash -ne $expected) { throw "OCR dependency checksum mismatch: $relative" }
}
$ocr=Join-Path $BuildDirectory 'libraries\ocr'
[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $ocrDependencies 'tesseract4android-4.9.0.aar'),$ocr)
& (Join-Path $PSScriptRoot 'tests\recognition\check-elf-alignment.ps1')
$libraryJars=@((Join-Path $pdfbox 'classes.jar'),(Join-Path $ocr 'classes.jar'),(Join-Path $ocrDependencies 'annotation-1.3.0.jar'))+@(Get-ChildItem -LiteralPath $dependencies -Filter '*.jar' | ForEach-Object FullName)
$compileClasspath=(@($androidJar)+$libraryJars) -join [System.IO.Path]::PathSeparator
# Backport Java 8/11 APIs used by current POI/Commons dependencies to Android 6+.
$desugarLibrary=Join-Path $dependencies 'build-tools\desugar_jdk_libs_nio-2.1.5.jar'
$desugarConfiguration=Join-Path $dependencies 'build-tools\desugar_jdk_libs_configuration_nio-2.1.5.jar'
$desugarRoot=Join-Path $BuildDirectory 'libraries\desugar'
[System.IO.Compression.ZipFile]::ExtractToDirectory($desugarConfiguration,$desugarRoot)
$desugarJson=Join-Path $desugarRoot 'META-INF\desugar\d8\desugar.json'
$desugarDex=Join-Path $BuildDirectory 'libraries\desugar-dex'
New-Item -ItemType Directory -Force -Path $desugarDex | Out-Null
# Windows SDK native tools require relative arguments for Unicode project directories.
$resourceInput=Join-Path $BuildDirectory ('resource-input-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $resourceInput | Out-Null
Copy-Item -LiteralPath (Join-Path $src 'res') -Destination (Join-Path $resourceInput 'res') -Recurse
Copy-Item -LiteralPath (Join-Path $src 'AndroidManifest.xml') -Destination $resourceInput
Push-Location -LiteralPath $BuildDirectory
try {
    $resourceName=Split-Path $resourceInput -Leaf
    & (Join-Path $bt 'aapt2.exe') compile --dir "$resourceName/res" -o 'resources.zip'
    Check 'Resource compilation'
    & (Join-Path $bt 'aapt2.exe') link -o 'unsigned.apk' -I $androidJar --manifest "$resourceName/AndroidManifest.xml" --java 'generated' 'resources.zip'
    Check 'Resource linking'
} finally { Pop-Location }
$sources=@(Get-ChildItem -LiteralPath (Join-Path $src 'java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 8 -classpath $compileClasspath -d (Join-Path $BuildDirectory 'classes') @sources
Check 'Java compilation'
& jar cf (Join-Path $BuildDirectory 'classes.jar') -C (Join-Path $BuildDirectory 'classes') .
Check 'Class packaging'
& (Join-Path $bt 'd8.bat') --release --min-api 23 --lib $androidJar --desugared-lib $desugarJson --output (Join-Path $BuildDirectory 'dex') (Join-Path $BuildDirectory 'classes.jar') @libraryJars
Check 'DEX compilation'
& java -Xmx2g -cp (Join-Path $bt 'lib\d8.jar') com.android.tools.r8.L8 --release --min-api 23 --lib $androidJar --desugared-lib $desugarJson --output $desugarDex $desugarLibrary $desugarConfiguration
Check 'Java API compatibility compilation'
$nextDex=@(Get-ChildItem -LiteralPath (Join-Path $BuildDirectory 'dex') -Filter '*.dex').Count+1
foreach($compatDex in Get-ChildItem -LiteralPath $desugarDex -Filter '*.dex' | Sort-Object Name) {
    Copy-Item -LiteralPath $compatDex.FullName -Destination (Join-Path $BuildDirectory ('dex\classes'+$nextDex+'.dex'))
    $nextDex++
}
& (Join-Path $PSScriptRoot 'tests\desugar-linkage\verify.ps1') -BuildDirectory $BuildDirectory -SdkRoot $SdkRoot
$archive=[System.IO.Compression.ZipFile]::Open((Join-Path $BuildDirectory 'unsigned.apk'),[System.IO.Compression.ZipArchiveMode]::Update)
try {
    # Android 5+ supports multiple DEX files natively. Do not silently discard classes2.dex.
    foreach($dex in Get-ChildItem -LiteralPath (Join-Path $BuildDirectory 'dex') -Filter '*.dex') {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$dex.FullName,$dex.Name) | Out-Null
    }
    # Windows aapt2 -A may emit backslashes inside nested ZIP entry names. AssetManager
    # requires POSIX names, so package these assets explicitly with forward slashes.
    foreach($assetRoot in @((Join-Path $pdfbox 'assets'),(Join-Path $dependencies 'licenses'),(Join-Path $ocrDependencies 'models'),(Join-Path $src 'assets'))) {
        foreach($asset in Get-ChildItem -LiteralPath $assetRoot -Recurse -File) {
            $assetName='assets/'+$asset.FullName.Substring($assetRoot.Length+1).Replace('\','/')
            if($archive.GetEntry($assetName)) { throw "Duplicate asset: $assetName" }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$asset.FullName,$assetName) | Out-Null
        }
    }
    # Android loads these directly from a 16 KB aligned APK; keep them uncompressed.
    $jniRoot=Join-Path $ocr 'jni'
    foreach($native in Get-ChildItem -LiteralPath $jniRoot -Recurse -Filter '*.so') {
        $nativeName='lib/'+$native.FullName.Substring($jniRoot.Length+1).Replace('\','/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$native.FullName,$nativeName,[System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
    foreach($requiredOcrAsset in @('assets/tessdata/chi_sim.traineddata','assets/tessdata/eng.traineddata','assets/ocr/ocr-font.ttf','lib/arm64-v8a/libtesseract.so','lib/armeabi-v7a/libtesseract.so','lib/x86_64/libtesseract.so','lib/x86/libtesseract.so')) {
        if(-not $archive.GetEntry($requiredOcrAsset)) { throw "Missing OCR asset: $requiredOcrAsset" }
    }
    foreach($requiredAsset in @('assets/com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt','assets/com/tom_roush/fontbox/resources/cmap/Adobe-GB1-UCS2','assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf','assets/PDFBox-Android-LICENSE.txt')) {
        if(-not $archive.GetEntry($requiredAsset)) { throw "Missing search asset: $requiredAsset" }
    }
    # Preserve Java resources needed by optional encryption providers; omit signed-JAR metadata.
    foreach($libraryJar in $libraryJars) {
        $jarArchive=[System.IO.Compression.ZipFile]::OpenRead($libraryJar)
        try {
            foreach($entry in $jarArchive.Entries) {
                if($entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('.class') -or $entry.FullName.StartsWith('META-INF/')) { continue }
                if($archive.GetEntry($entry.FullName)) { continue }
                $destination=$archive.CreateEntry($entry.FullName)
                $inputStream=$entry.Open()
                $outputStream=$destination.Open()
                try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
            }
        } finally { $jarArchive.Dispose() }
    }
} finally { $archive.Dispose() }
Push-Location -LiteralPath $BuildDirectory
try {
    & (Join-Path $bt 'zipalign.exe') -f -P 16 4 'unsigned.apk' 'aligned.apk'
    Check 'APK alignment'
} finally { Pop-Location }
$key=Join-Path $BuildDirectory 'local-test.keystore'
if(-not (Test-Path -LiteralPath $key)) {
    & keytool -genkeypair -keystore $key -storepass android -keypass android -alias localtest -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=QingYue Local Build, O=Personal, C=CN'
    Check 'Local signing key generation'
}
Push-Location -LiteralPath $BuildDirectory
try {
    & (Join-Path $bt 'apksigner.bat') sign --ks 'local-test.keystore' --ks-key-alias localtest --ks-pass pass:android --key-pass pass:android --out 'signed.apk' 'aligned.apk'
    Check 'APK signing'
    & (Join-Path $bt 'apksigner.bat') verify --verbose 'signed.apk'
    Check 'Signature verification'
    & (Join-Path $bt 'zipalign.exe') -c -P 16 4 'signed.apk'
    Check 'Alignment verification'
} finally { Pop-Location }
Copy-Item -LiteralPath (Join-Path $BuildDirectory 'signed.apk') -Destination $OutputApk -Force
Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256

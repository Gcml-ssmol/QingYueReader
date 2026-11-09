param([string]$BuildDirectory = (Join-Path $PSScriptRoot 'work'))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$run = Join-Path (Resolve-Path $BuildDirectory).Path ([guid]::NewGuid().ToString('N'))
$stubOut = Join-Path $run 'stub-classes'
$aarOut = Join-Path $run 'aar-classes'
New-Item -ItemType Directory -Force -Path $stubOut,$aarOut | Out-Null
$production = @('OcrCancellation.java','OcrDeskew.java','EpubOcrSession.java' |
    ForEach-Object { Join-Path $root "app/src/main/java/cn/qingyue/pdf/$_" })
$stubs = @(Get-ChildItem (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' |
    ForEach-Object FullName)
$test = Join-Path $PSScriptRoot 'EpubOcrLogicTest.java'

# JVM-only behavior test. These stubs record OCR engine calls; no Android JNI is loaded.
& javac -encoding UTF-8 --release 8 -d $stubOut @stubs @production $test
if ($LASTEXITCODE -ne 0) { throw 'EPUB OCR desktop stub compilation failed' }
& java -cp $stubOut cn.qingyue.pdf.EpubOcrLogicTest
if ($LASTEXITCODE -ne 0) { throw 'EPUB OCR desktop behavior test failed' }

# Compile production code again against the shipped Tesseract AAR signatures.
$aar = Join-Path $root 'third-party/ocr/tesseract4android-4.9.0.aar'
$jar = Join-Path $run 'tesseract-classes.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($aar)
try {
    $entry = $zip.GetEntry('classes.jar')
    if ($null -eq $entry) { throw 'Tesseract AAR lacks classes.jar' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $jar)
} finally { $zip.Dispose() }
$otherStubs = @($stubs | Where-Object { $_ -notlike '*TessBaseAPI.java' })
& javac -encoding UTF-8 --release 8 -classpath $jar -d $aarOut @otherStubs @production
if ($LASTEXITCODE -ne 0) { throw 'Production OCR source did not compile against shipped Tesseract AAR' }
Write-Host 'EpubOcrLogicTest passed; shipped AAR API compile passed (no JNI/device execution).'

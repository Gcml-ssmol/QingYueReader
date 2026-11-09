param([string]$BuildDirectory = (Join-Path $PSScriptRoot '..\..\work\pdf-epub-test'))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$run = Join-Path (Resolve-Path $BuildDirectory).Path ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path (Join-Path $run 'classes'),(Join-Path $run 'cache') | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pdfbox = Join-Path $run 'pdfbox'
if (!(Test-Path $pdfbox)) { [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $root 'third-party/pdfbox-android-2.0.27.0.aar'), $pdfbox) }
$cp = (@((Join-Path $run 'classes'),(Join-Path $pdfbox 'classes.jar')) + @(Get-ChildItem (Join-Path $root 'third-party') -Filter '*.jar' | ForEach-Object FullName)) -join [IO.Path]::PathSeparator
$android = @(Get-ChildItem (Join-Path $root 'tests/pdf-search-smoke/android') -Recurse -Filter '*.java' | Where-Object Name -ne 'Bitmap.java' | ForEach-Object FullName)
$stubs = @(Get-ChildItem (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources = @('OcrCancellation.java','OcrHocr.java','PdfNavigationIndex.java','EpubOcrText.java','EpubBook.java','EpubWriter.java','PdfEpubExporter.java' | ForEach-Object { Join-Path $root "app/src/main/java/cn/qingyue/pdf/$_" })
& javac -encoding UTF-8 --release 8 -classpath $cp -d (Join-Path $run 'classes') @android @stubs @sources (Join-Path $PSScriptRoot 'PdfEpubTest.java')
if ($LASTEXITCODE -ne 0) { throw 'PDF EPUB desktop test compilation failed' }
& java "-Dsmoke.root=$run" "-Dsmoke.assets=$(Join-Path $pdfbox 'assets')" -cp $cp cn.qingyue.pdf.PdfEpubTest
if ($LASTEXITCODE -ne 0) { throw 'PDF EPUB desktop test failed' }

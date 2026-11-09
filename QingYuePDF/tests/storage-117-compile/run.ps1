param(
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$ReaderSource = '',
    [string]$BuildDirectory = (Join-Path $env:TEMP 'qingyue-storage-javac')
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$sdk=(Resolve-Path -LiteralPath $SdkRoot).Path
$android=Join-Path $sdk 'platforms/android-35/android.jar'
$aapt2=Join-Path $sdk 'build-tools/35.0.0/aapt2.exe'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$scratch=Join-Path (Resolve-Path -LiteralPath $BuildDirectory).Path ('run-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $scratch,(Join-Path $scratch 'generated'),(Join-Path $scratch 'classes'),(Join-Path $scratch 'pdfbox'),(Join-Path $scratch 'ocr') | Out-Null
Copy-Item -LiteralPath (Join-Path $project 'app/src/main/res') -Destination (Join-Path $scratch 'res') -Recurse
Copy-Item -LiteralPath (Join-Path $project 'app/src/main/AndroidManifest.xml') -Destination (Join-Path $scratch 'AndroidManifest.xml')
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $project 'third-party/pdfbox-android-2.0.27.0.aar'),(Join-Path $scratch 'pdfbox'))
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $project 'third-party/ocr/tesseract4android-4.9.0.aar'),(Join-Path $scratch 'ocr'))
Push-Location $scratch
try {
    & $aapt2 compile --dir res -o resources.zip
    if($LASTEXITCODE -ne 0){throw 'aapt2 compile failed'}
    & $aapt2 link -o unsigned.apk -I $android --manifest AndroidManifest.xml --java generated resources.zip
    if($LASTEXITCODE -ne 0){throw 'aapt2 link failed'}
} finally {Pop-Location}
$jars=@($android,(Join-Path $scratch 'pdfbox/classes.jar'),(Join-Path $scratch 'ocr/classes.jar'),(Join-Path $project 'third-party/ocr/annotation-1.3.0.jar'))
$jars+=@(Get-ChildItem -LiteralPath (Join-Path $project 'third-party') -Filter '*.jar' | ForEach-Object FullName)
$cp=$jars -join [IO.Path]::PathSeparator
$sources=@(Get-ChildItem -LiteralPath (Join-Path $project 'app/src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if($ReaderSource){
    $reader=(Resolve-Path -LiteralPath $ReaderSource).Path
    if(-not (Test-Path -LiteralPath $reader -PathType Container)){$reader=Split-Path -Parent $reader}
    $names=@('PdfReaderView.java','NightPageDisplay.java','NightInk.java','ReaderViewportMath.java','InkStrokeRenderer.java','PdfTextBoxes.java')
    $sources=@($sources | Where-Object { $names -notcontains [IO.Path]::GetFileName($_) })
    $sources+=@($names | ForEach-Object { Join-Path $reader $_ })
}
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $scratch 'generated') -Recurse -Filter '*.java' | ForEach-Object FullName)
$argfile=Join-Path $scratch 'javac.args'
$compileArgs=@('-encoding','UTF-8','--release','8','-classpath',$cp,'-d',(Join-Path $scratch 'classes'))+$sources
[IO.File]::WriteAllLines($argfile,($compileArgs | ForEach-Object {'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
& javac '-J-Dfile.encoding=UTF-8' ('@'+$argfile)
if($LASTEXITCODE -ne 0){throw 'full app javac failed'}
"PASS: Android 35 / Java 8 source compilation (no APK signing). $scratch"

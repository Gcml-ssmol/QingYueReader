param([Parameter(Mandatory=$true)][string]$BuildDirectory)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$production=Join-Path $project 'app/src/main/java/cn/qingyue/pdf'
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$BuildDirectory=(Resolve-Path -LiteralPath $BuildDirectory).Path
$run=Join-Path $BuildDirectory ('storage-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run,(Join-Path $run 'classes') | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $project 'third-party/pdfbox-android-2.0.27.0.aar'),(Join-Path $run 'pdfbox'))
$cp=(@((Join-Path $run 'classes'),(Join-Path $run 'pdfbox/classes.jar'))+@(Get-ChildItem -LiteralPath (Join-Path $project 'third-party') -Filter '*.jar' | ForEach-Object FullName)) -join [IO.Path]::PathSeparator
$sources=@{}
foreach($root in @((Join-Path $project 'tests/pdf-search-smoke/android'),(Join-Path $project 'tests/library-manager/android'),(Join-Path $project 'tests/pdf-page-insertion/android'))){
    foreach($file in Get-ChildItem -LiteralPath $root -Filter '*.java' -Recurse){$relative=$file.FullName.Substring($root.Length).Replace('\','/');$sources[$relative]=$file.FullName}
}
$sources['/graphics/pdf/PdfRenderer.java']=Join-Path $project 'tests/pdf-search-smoke/android/graphics/pdf/PdfRenderer.java'
$names=@('PdfPageInsertion','PdfBlankPageEditor','DocumentEditLease','LibraryItem','LibraryFolder','LibraryIndex','LibraryStore','TrashManager','BackupArchive','BackupManager','NoteDocument','NoteStore','InkPage','InkStore','InkHistoryRegistry','ReaderDocument','ReaderSourceLink','ReaderStore','OcrSidecar','PdfAnnotationExporter','PdfTextBoxes','InkStrokeRenderer','NightInk','PageMath')
$all=@($sources.Values)+@($names | ForEach-Object {Join-Path $production ($_.ToString()+'.java')})+@((Join-Path $PSScriptRoot 'StorageAuditTest.java'))
$argsFile=Join-Path $run 'javac.args'
$arguments=@('-encoding','UTF-8','--release','17','-classpath',$cp,'-d',(Join-Path $run 'classes'))+$all
[IO.File]::WriteAllLines($argsFile,($arguments | ForEach-Object {'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
& javac '-J-Dfile.encoding=UTF-8' ('@'+$argsFile) 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
if($LASTEXITCODE -ne 0){throw 'Storage audit compilation failed'}
& java "-Dsmoke.assets=$(Join-Path $run 'pdfbox/assets')" "-Dsmoke.font=$(Join-Path $project 'app/src/main/assets/ocr/ocr-font.ttf')" -cp $cp cn.qingyue.pdf.StorageAuditTest 2>&1 | Tee-Object -FilePath (Join-Path $run 'test.log')
if($LASTEXITCODE -ne 0){throw 'Storage audit regression failed'}
"PASS: storage audit. Logs: $run"

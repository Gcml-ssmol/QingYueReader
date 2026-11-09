param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$BuildDirectory=(Join-Path $PSScriptRoot '../../work/document-page-api-tests')
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$androidJar=Join-Path $SdkRoot 'platforms/android-35/android.jar'
if(-not (Test-Path $androidJar)){throw "Missing Android 35 SDK: $androidJar"}
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$jars=@($androidJar)+@(Get-ChildItem (Join-Path $root 'third-party') -Filter '*.jar' | ForEach-Object FullName)
$classpath=$jars -join [IO.Path]::PathSeparator
$src=Join-Path $root 'app/src/main/java/cn/qingyue/pdf'
& javac -encoding UTF-8 --release 8 -classpath $classpath -d $BuildDirectory `
    (Join-Path $src 'DocumentPagePlan.java') (Join-Path $src 'DocumentImport.java') `
    (Join-Path $src 'DocumentText.java') (Join-Path $src 'EpubText.java') `
    (Join-Path $PSScriptRoot 'ImportLayoutOptionsTest.java')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp ($BuildDirectory+[IO.Path]::PathSeparator+$classpath) cn.qingyue.pdf.ImportLayoutOptionsTest
exit $LASTEXITCODE

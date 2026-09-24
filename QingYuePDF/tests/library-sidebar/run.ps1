param([string]$BuildDirectory=(Join-Path $PSScriptRoot '../../../work/library-sidebar-tests'))
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$names=@('LibraryItem','LibraryFolder','LibraryQuery','LibrarySidebarModel')
$sources=@($names | ForEach-Object { Join-Path $project "app/src/main/java/cn/qingyue/pdf/$_.java" })
& javac --release 8 -encoding UTF-8 -d $BuildDirectory @sources (Join-Path $PSScriptRoot 'LibrarySidebarModelTest.java')
if($LASTEXITCODE -ne 0){throw 'Sidebar model compilation failed'}
& java -cp $BuildDirectory LibrarySidebarModelTest
if($LASTEXITCODE -ne 0){throw 'Sidebar model test failed'}

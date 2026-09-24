param([string]$BuildDirectory = (Join-Path $env:TEMP ('qingyue-tool-preferences-' + [guid]::NewGuid().ToString('N'))))
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $base '..\..\app\src\main\java\cn\qingyue\pdf\ToolPreferences.java'
$output = Join-Path $BuildDirectory 'classes'
New-Item -ItemType Directory -Force -Path $output | Out-Null
javac -encoding UTF-8 --release 8 -d $output (Join-Path $base 'android\content\SharedPreferences.java') $source (Join-Path $base 'ToolPreferencesTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java -cp $output cn.qingyue.pdf.ToolPreferencesTest
exit $LASTEXITCODE

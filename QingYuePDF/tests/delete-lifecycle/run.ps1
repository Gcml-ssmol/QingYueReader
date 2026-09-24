$ErrorActionPreference = 'Stop'
$project = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$main = Get-Content (Join-Path $project 'app\src\main\java\cn\qingyue\pdf\MainActivity.java') -Raw
$loader = Get-Content (Join-Path $project 'app\src\main\java\cn\qingyue\pdf\LibraryThumbnailLoader.java') -Raw

if ($main -notmatch 'libraryAdapter\.dispose\(\)' -or
    $main -notmatch 'snapshot=new ArrayList<>\(items\)' -or
    $main -notmatch 'getItem\(int position\).*snapshot\.get\(position\)' -or
    $main -notmatch 'getWrappedAdapter\(\)==this' -or
    $main -notmatch 'libraryAdapter!=this\|\|h\.token!=token' -or
    $main -notmatch 'thumbnails\.invalidate\(id\)' -or
    $loader -notmatch 'currentGeneration\(item\.id, key\)') {
    throw 'Deleted/replaced library rows can still receive stale thumbnail work'
}
if ($main -notmatch 'quickModes=\{0,1,10,11,2,4\}' -or
    $main -notmatch 'final int mode=quickModes\[i\]' -or
    $main -notmatch 'setActive\(quickModes\[i\]==inkMode\)') {
    throw 'Quick eraser must select and highlight local eraser mode 4'
}
Write-Host 'Delete lifecycle and quick eraser static source checks passed'

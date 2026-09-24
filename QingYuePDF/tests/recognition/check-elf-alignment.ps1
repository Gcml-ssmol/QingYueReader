$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Resolve-Path (Join-Path $PSScriptRoot '..\..')
$archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $root 'third-party\ocr\tesseract4android-4.9.0.aar'))
try {
    foreach($entry in $archive.Entries) {
        if(-not $entry.FullName.StartsWith('jni/arm64-v8a/') -or -not $entry.FullName.EndsWith('.so')) { continue }
        $memory=[IO.MemoryStream]::new(); $stream=$entry.Open(); try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
        $reader=[IO.BinaryReader]::new($memory)
        try {
            $memory.Position=32; $header=[int64]$reader.ReadUInt64()
            $memory.Position=54; $size=$reader.ReadUInt16(); $count=$reader.ReadUInt16()
            $alignments=@()
            for($i=0;$i -lt $count;$i++) {
                $memory.Position=$header+$i*$size
                $type=$reader.ReadUInt32()
                if($type -eq 1) { $memory.Position=$header+$i*$size+48; $alignments+=($reader.ReadUInt64()) }
            }
            Write-Output "$($entry.FullName): PT_LOAD alignment $($alignments -join ', ')"
            if($alignments.Count -eq 0 -or @($alignments | Where-Object { $_ -lt 16384 }).Count -gt 0) { throw 'Native library is not 16 KB page aligned' }
        } finally { $reader.Dispose() }
    }
} finally { $archive.Dispose() }

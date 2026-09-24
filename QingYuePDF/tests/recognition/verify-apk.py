"""Check OCR assets and native-library bytes in the delivered APK (Python 3 stdlib)."""
import hashlib
import struct
import sys
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


apk_path = Path(sys.argv[1])
root = Path(__file__).resolve().parents[2]
required = {
    "assets/ocr/ocr-font.ttf": root / "app/src/main/assets/ocr/ocr-font.ttf",
    "assets/tessdata/chi_sim.traineddata": root / "third-party/ocr/models/tessdata/chi_sim.traineddata",
    "assets/tessdata/eng.traineddata": root / "third-party/ocr/models/tessdata/eng.traineddata",
}
for notice in (root / "third-party/licenses").iterdir():
    if notice.is_file():
        required["assets/" + notice.name] = notice

with zipfile.ZipFile(apk_path) as apk, zipfile.ZipFile(root / "third-party/ocr/tesseract4android-4.9.0.aar") as aar, apk_path.open("rb") as raw:
    for name, source in required.items():
        assert digest(apk.read(name)) == digest(source.read_bytes()), name + " differs from source"
    libraries = [name for name in aar.namelist() if name.startswith("jni/") and name.endswith(".so")]
    assert {name.split("/")[1] for name in libraries} == {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    for name in libraries:
        entry = apk.getinfo("lib/" + name[4:])
        assert entry.compress_type == zipfile.ZIP_STORED, name + " is compressed"
        assert digest(apk.read(entry)) == digest(aar.read(name)), name + " differs from dependency"
        raw.seek(entry.header_offset + 26)
        filename_length, extra_length = struct.unpack("<HH", raw.read(4))
        data_offset = entry.header_offset + 30 + filename_length + extra_length
        assert data_offset % 16384 == 0, name + " is not 16 KB ZIP aligned"
    assert len(libraries) == 16
print(f"PASS OCR APK assets: {len(required)} files match source; 16 native libraries across 4 ABIs match AAR, uncompressed and 16 KB ZIP aligned")

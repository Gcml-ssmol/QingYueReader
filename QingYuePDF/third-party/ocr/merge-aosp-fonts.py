"""Combine official Apache-2.0 AOSP CJK and Latin static TrueType fonts.

Requires fontTools 4.65.0. The resulting modified font is renamed.
Usage: python merge-aosp-fonts.py DroidSansFallback.ttf DroidSansMono.ttf ocr-font.ttf
"""
import copy
import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem


def main() -> None:
    fallback, mono, output = map(Path, sys.argv[1:4])
    base = TTFont(fallback)
    latin = TTFont(mono)
    scale_upem(latin, base["head"].unitsPerEm)
    base_order = base.getGlyphOrder()
    latin_order = latin.getGlyphOrder()
    names = {name: "latin_" + str(index) for index, name in enumerate(latin_order)}
    added = [names[name] for name in latin_order]
    for name in latin_order:
        glyph = copy.deepcopy(latin["glyf"][name])
        if glyph.isComposite():
            for component in glyph.components:
                component.glyphName = names[component.glyphName]
        base["glyf"].glyphs[names[name]] = glyph
        base["hmtx"].metrics[names[name]] = latin["hmtx"].metrics[name]
    base.setGlyphOrder(base_order + added)
    base["maxp"].numGlyphs = len(base_order) + len(added)
    base["hhea"].numberOfHMetrics = base["maxp"].numGlyphs
    latin_cmap = latin.getBestCmap()
    for table in base["cmap"].tables:
        if table.isUnicode():
            for codepoint, name in latin_cmap.items():
                table.cmap.setdefault(codepoint, names[name])
    renamed = {
        1: "QingYue OCR Droid Sans",
        2: "Regular",
        3: "QingYueOCRDroidSans-Regular",
        4: "QingYue OCR Droid Sans Regular",
        6: "QingYueOCRDroidSans-Regular",
        16: "QingYue OCR Droid Sans",
        17: "Regular",
    }
    for record in base["name"].names:
        if record.nameID in renamed:
            record.string = renamed[record.nameID].encode(
                "utf_16_be" if record.isUnicode() else "ascii"
            )
    base.recalcTimestamp = False
    base["head"].modified = base["head"].created
    base.save(output)


if __name__ == "__main__":
    main()

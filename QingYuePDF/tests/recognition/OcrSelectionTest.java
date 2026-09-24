package cn.qingyue.pdf;

public final class OcrSelectionTest {
    public static void main(String[] args) {
        check("chi_sim", false, 0, 0);
        check("chi_sim+eng", true, 1, 1);
        check("eng", false, 2, 0);
        try { OcrSelection.fromIndices(3, 0); throw new AssertionError("language accepted"); }
        catch (IllegalArgumentException expected) { }
        try { OcrSelection.fromIndices(0, 2); throw new AssertionError("layout accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("OcrSelectionTest passed");
    }

    private static void check(String models, boolean sparse, int language, int layout) {
        OcrSelection selected = OcrSelection.fromIndices(language, layout);
        if (!models.equals(selected.models) || sparse != selected.sparse)
            throw new AssertionError("OCR selection mismatch");
    }
}

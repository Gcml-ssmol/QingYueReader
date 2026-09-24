package cn.qingyue.pdf;

/** UI indices are converted to explicit engine settings in one place. */
final class OcrSelection {
    final String models;
    final boolean sparse;

    private OcrSelection(String models, boolean sparse) {
        this.models = models;
        this.sparse = sparse;
    }

    static OcrSelection fromIndices(int language, int layout) {
        String models;
        switch (language) {
            case 0: models = "chi_sim"; break;
            case 1: models = "chi_sim+eng"; break;
            case 2: models = "eng"; break;
            default: throw new IllegalArgumentException("Unsupported OCR language");
        }
        if (layout != 0 && layout != 1)
            throw new IllegalArgumentException("Unsupported OCR layout");
        return new OcrSelection(models, layout == 1);
    }
}

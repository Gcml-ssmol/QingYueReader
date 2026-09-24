package cn.qingyue.pdf;

import android.content.SharedPreferences;

/** Keeps each drawing tool's color independent while preserving legacy pen colors. */
final class ToolPreferences {
    static final int BLACK = 0xff000000;
    static final int YELLOW = 0xffffeb3b;

    private ToolPreferences() { }

    static String pdfToolName(int mode) {
        switch (mode) {
            case 1: return "pen";
            case 2: return "marker";
            case 10: return "ballpoint";
            case 11: return "pencil";
            case 6: return "line";
            case 7: return "rectangle";
            case 8: return "ellipse";
            case 9: return "arrow";
            default: return null;
        }
    }

    static String pdfSizeKey(int mode) {
        switch (mode) {
            case 1: return "penSize";
            case 2: return "markerSize";
            case 10: return "ballpointSize";
            case 11: return "pencilSize";
            case 3: return "eraserSize";
            case 4: return "localEraserSize";
            case 6: return "lineSize";
            case 7: return "rectangleSize";
            case 8: return "ellipseSize";
            case 9: return "arrowSize";
            default: return null;
        }
    }

    static int pdfSizeType(int mode) { return mode == 3 || mode == 4 ? 3 : mode == 2 ? 2 : 1; }

    static int color(SharedPreferences preferences, String tool, String legacyKey, int legacyDefault) {
        String key = "marker".equals(tool) ? "markerColor" :
                ("pen".equals(tool) ? "penColor" : tool + "Color");
        int fallback = "marker".equals(tool) ? YELLOW :
                "pen".equals(tool) ? preferences.getInt(legacyKey, legacyDefault) : BLACK;
        return preferences.getInt(key, fallback) | 0xff000000;
    }

    static void saveColor(SharedPreferences preferences, String tool, int color) {
        if ("eraser".equals(tool) || "hand".equals(tool) || "select".equals(tool)) return;
        String key = "marker".equals(tool) ? "markerColor" :
                ("pen".equals(tool) ? "penColor" : tool + "Color");
        preferences.edit().putInt(key, color | 0xff000000).apply();
    }
}

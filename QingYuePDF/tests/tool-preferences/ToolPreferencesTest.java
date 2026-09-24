package cn.qingyue.pdf;

import android.content.SharedPreferences;
import java.util.HashMap;

public final class ToolPreferencesTest {
    private static final class Memory implements SharedPreferences, SharedPreferences.Editor {
        final HashMap<String, Integer> values = new HashMap<>();
        public int getInt(String key, int fallback) { return values.getOrDefault(key, fallback); }
        public SharedPreferences.Editor edit() { return this; }
        public SharedPreferences.Editor putInt(String key, int value) { values.put(key, value); return this; }
        public void apply() { }
    }
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError(String.format("expected %08x, got %08x", expected, actual));
    }
    private static void equal(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        Memory pdf = new Memory();
        pdf.values.put("inkColor", 0xff123456);
        equal(0xff123456, ToolPreferences.color(pdf, "pen", "inkColor", ToolPreferences.BLACK));
        equal(ToolPreferences.YELLOW, ToolPreferences.color(pdf, "marker", "inkColor", ToolPreferences.BLACK));
        ToolPreferences.saveColor(pdf, "pen", ToolPreferences.BLACK);
        ToolPreferences.saveColor(pdf, "marker", ToolPreferences.YELLOW);
        ToolPreferences.saveColor(pdf, "eraser", 0xffabcdef);
        equal(ToolPreferences.BLACK, ToolPreferences.color(pdf, "pen", "inkColor", ToolPreferences.BLACK));
        equal(ToolPreferences.YELLOW, ToolPreferences.color(pdf, "marker", "inkColor", ToolPreferences.BLACK));
        equal(0xff123456, pdf.getInt("inkColor", 0));
        equal("line", ToolPreferences.pdfToolName(6));
        equal("rectangle", ToolPreferences.pdfToolName(7));
        equal("ellipse", ToolPreferences.pdfToolName(8));
        equal("arrow", ToolPreferences.pdfToolName(9));
        equal("ballpoint", ToolPreferences.pdfToolName(10));
        equal("pencil", ToolPreferences.pdfToolName(11));
        equal("ballpointSize", ToolPreferences.pdfSizeKey(10));
        equal("pencilSize", ToolPreferences.pdfSizeKey(11));
        equal(1, ToolPreferences.pdfSizeType(10));
        equal(1, ToolPreferences.pdfSizeType(11));
        ToolPreferences.saveColor(pdf, "ballpoint", 0xff234567);
        ToolPreferences.saveColor(pdf, "pencil", 0xff765432);
        equal(0xff234567, ToolPreferences.color(pdf, "ballpoint", "inkColor", ToolPreferences.BLACK));
        equal(0xff765432, ToolPreferences.color(pdf, "pencil", "inkColor", ToolPreferences.BLACK));
        equal(null, ToolPreferences.pdfToolName(4));
        equal("eraserSize", ToolPreferences.pdfSizeKey(3));
        equal("localEraserSize", ToolPreferences.pdfSizeKey(4));
        for (int mode = 6; mode <= 9; mode++) {
            String name = ToolPreferences.pdfToolName(mode);
            equal(name + "Size", ToolPreferences.pdfSizeKey(mode));
            equal(1, ToolPreferences.pdfSizeType(mode));
            ToolPreferences.saveColor(pdf, name, 0xff100000 + mode);
        }
        equal(3, ToolPreferences.pdfSizeType(4));
        for (int mode = 6; mode <= 9; mode++)
            equal(0xff100000 + mode, ToolPreferences.color(pdf, ToolPreferences.pdfToolName(mode), "inkColor", ToolPreferences.BLACK));

        Memory note = new Memory();
        note.values.put("color", 0xffaa5500);
        equal(0xffaa5500, ToolPreferences.color(note, "pen", "color", ToolPreferences.BLACK));
        equal(ToolPreferences.YELLOW, ToolPreferences.color(note, "marker", "color", ToolPreferences.BLACK));
        ToolPreferences.saveColor(note, "line", 0xff345678);
        ToolPreferences.saveColor(note, "circle", 0xffabcdef);
        ToolPreferences.saveColor(note, "pen", ToolPreferences.BLACK);
        equal(0xff345678, ToolPreferences.color(note, "line", "color", ToolPreferences.BLACK));
        equal(0xffabcdef, ToolPreferences.color(note, "circle", "color", ToolPreferences.BLACK));
        equal(ToolPreferences.BLACK, ToolPreferences.color(note, "pen", "color", ToolPreferences.BLACK));
        equal(ToolPreferences.YELLOW, ToolPreferences.color(note, "marker", "color", ToolPreferences.BLACK));
    }
}

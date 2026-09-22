package cn.qingyue.pdf;

import java.text.Normalizer;
import java.util.Locale;

/** Small, Android-independent text matcher. It never keeps a document-wide text index. */
final class SearchText {
    private SearchText() {}

    static final class Match {
        final String snippet;
        final int count;
        Match(String snippet, int count) { this.snippet = snippet; this.count = count; }
    }

    static String normalize(String text) {
        if (text == null || text.length() == 0) return "";
        String input = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(input.length());
        boolean space = false;
        int previous = -1;
        for (int i = 0; i < input.length();) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            // These formatting characters are commonly inserted in extracted PDF text.
            if (cp == 0x200b || cp == 0xfeff || cp == 0x00ad) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space = out.length() > 0; continue; }
            // Chinese line wraps and glyph spacing should not split a searchable phrase.
            if (space && !(isCjk(previous) && isCjk(cp))) out.append(' ');
            out.appendCodePoint(cp);
            previous = cp;
            space = false;
        }
        return out.toString();
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0xf900 && cp <= 0xfaff)
                || (cp >= 0x20000 && cp <= 0x323af) || (cp >= 0x3040 && cp <= 0x30ff)
                || (cp >= 0xac00 && cp <= 0xd7af);
    }

    static Match find(String text, String query) {
        String display = normalize(text);
        String needle = normalize(query).toLowerCase(Locale.ROOT);
        if (needle.length() == 0 || display.length() == 0) return null;
        String folded = display.toLowerCase(Locale.ROOT);
        int first = folded.indexOf(needle);
        if (first < 0) return null;
        int count = 0;
        for (int offset = first; offset >= 0; offset = folded.indexOf(needle, offset + needle.length())) count++;

        // Case conversion can expand a character (e.g. capital dotted I), so map folded offsets
        // back to the displayed text rather than slicing with potentially shifted indices.
        int displayFirst = displayOffset(display, first);
        int displayEnd = displayOffset(display, first + needle.length());
        int begin = Math.max(0, displayFirst - 42);
        int end = Math.min(display.length(), Math.min(begin + 200, Math.max(displayEnd + 65, begin + 120)));
        if (begin > 0 && Character.isLowSurrogate(display.charAt(begin))) begin--;
        if (end < display.length() && Character.isLowSurrogate(display.charAt(end))) end++;
        String snippet = (begin > 0 ? "…" : "") + display.substring(begin, end) + (end < display.length() ? "…" : "");
        return new Match(snippet, count);
    }

    private static int displayOffset(String display, int foldedOffset) {
        int folded = 0;
        for (int i = 0; i < display.length();) {
            if (folded >= foldedOffset) return i;
            int next = i + Character.charCount(display.codePointAt(i));
            folded += display.substring(i, next).toLowerCase(Locale.ROOT).length();
            i = next;
        }
        return display.length();
    }
}

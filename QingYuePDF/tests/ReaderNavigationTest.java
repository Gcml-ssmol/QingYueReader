package cn.qingyue.pdf;

/** Gesture-policy regression checks, runnable on the JVM without Android. */
public final class ReaderNavigationTest {
    public static void main(String[] args) {
        float threshold = 72;

        // A fitted page has no horizontal pan range: deliberate swipes turn pages.
        expect(1, -130, 5, threshold, 0, 0, "fitted left swipe advances");
        expect(-1, 130, -5, threshold, 0, 0, "fitted right swipe goes back");
        expect(1, -threshold, 0, threshold, 0, 0, "exact left threshold");
        expect(-1, threshold, 0, threshold, 0, 0, "exact right threshold");
        expect(0, -threshold + 0.1f, 0, threshold, 0, 0, "short left swipe");
        expect(0, threshold - 0.1f, 0, threshold, 0, 0, "short right swipe");
        expect(0, 0, 0, threshold, 0, 0, "tap does not navigate");

        // Vertical scrolling and ambiguous diagonal movements retain their gesture.
        expect(0, 0, 300, threshold, 0, 0, "vertical down");
        expect(0, 0, -300, threshold, 0, 0, "vertical up");
        for (int xSign : new int[] { -1, 1 }) {
            for (int ySign : new int[] { -1, 1 }) {
                expect(0, 120 * xSign, 120 * ySign, threshold, 0, 0, "diagonal quadrant");
                expect(0, 100 * xSign, 200 * ySign, threshold, 0, 0, "mostly vertical quadrant");
            }
        }

        // At zoom, a gesture that starts away from an edge pans even if very long.
        expect(0, -200, 0, threshold, 0, 300, "center pans left");
        expect(0, 200, 0, threshold, 0, 300, "center pans right");
        expect(0, -1000, 0, threshold, -150, 300, "long pan toward right edge");
        expect(0, 1000, 0, threshold, 150, 300, "long pan toward left edge");
        expect(0, -200, 0, threshold, -297, 300, "outside right edge tolerance pans");
        expect(0, 200, 0, threshold, 297, 300, "outside left edge tolerance pans");

        // pan=-max reveals the right edge; an outward left swipe advances.
        expect(1, -200, 10, threshold, -300, 300, "right edge outward advances");
        expect(0, 200, 0, threshold, -300, 300, "right edge inward pans");
        expect(1, -200, 0, threshold, -298, 300, "right edge rounding tolerance");

        // pan=+max reveals the left edge; an outward right swipe goes back.
        expect(-1, 200, -10, threshold, 300, 300, "left edge outward goes back");
        expect(0, -200, 0, threshold, 300, 300, "left edge inward pans");
        expect(-1, 200, 0, threshold, 298, 300, "left edge rounding tolerance");

        // Edge position never overrides short or vertical gesture classification.
        expect(0, -50, 0, threshold, -300, 300, "short right-edge gesture");
        expect(0, 50, 0, threshold, 300, 300, "short left-edge gesture");
        expect(0, -200, 200, threshold, -300, 300, "diagonal right-edge gesture");
        expect(0, 200, -200, threshold, 300, 300, "diagonal left-edge gesture");

        System.out.println("PASS: fitted-page swipes, threshold, vertical/diagonal rejection, zoomed panning and outward edge navigation");
    }

    private static void expect(int expected, float dx, float dy, float threshold,
            float initialPan, float maxPan, String message) {
        int actual = ReaderNavigation.horizontalPage(dx, dy, threshold, initialPan, maxPan);
        if (actual != expected) {
            throw new AssertionError(message + ": " + actual + " != " + expected);
        }
    }
}

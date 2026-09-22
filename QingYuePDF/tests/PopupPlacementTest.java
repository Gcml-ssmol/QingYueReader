package cn.qingyue.pdf;

import java.util.Arrays;
import java.util.Random;

public class PopupPlacementTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(String name, int[] expected, int[] actual) {
        check(Arrays.equals(expected, actual), name + ": expected "
                + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
    }

    private static void invalid(int left, int top, int right, int bottom) {
        try {
            PopupPlacement.place(left, top, right, bottom, 0, 0, 48, 48, 320, 400, 8, 8);
            throw new AssertionError("Accepted empty/inverted visible screen");
        } catch (IllegalArgumentException expected) {}
    }

    public static void main(String[] args) {
        expect("portrait phone", new int[]{20, 108, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, 156, 52, 204, 100, 320, 480, 12, 8));
        expect("tablet", new int[]{592, 140, 480, 560},
                PopupPlacement.place(0, 24, 1600, 1000, 808, 80, 856, 128, 480, 560, 16, 12));
        expect("left edge", new int[]{12, 108, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, 0, 52, 48, 100, 320, 480, 12, 8));
        expect("right edge", new int[]{28, 108, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, 312, 52, 360, 100, 320, 480, 12, 8));
        expect("bottom toolbar opens above", new int[]{20, 252, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, 156, 740, 204, 788, 320, 480, 12, 8));
        expect("keyboard limits height", new int[]{20, 108, 320, 240},
                PopupPlacement.place(0, 24, 360, 360, 156, 52, 204, 100, 320, 480, 12, 8));
        expect("keyboard makes above roomier", new int[]{20, 36, 320, 186},
                PopupPlacement.place(0, 24, 360, 360, 156, 230, 204, 278, 320, 480, 12, 8));
        expect("screen offset", new int[]{420, 308, 320, 480},
                PopupPlacement.place(400, 224, 760, 1000, 556, 252, 604, 300, 320, 480, 12, 8));
        expect("negative screen offset", new int[]{-380, -92, 320, 480},
                PopupPlacement.place(-400, -176, -40, 600, -244, -148, -196, -100, 320, 480, 12, 8));
        expect("undersized window", new int[]{104, 205, 1, 1},
                PopupPlacement.place(100, 200, 109, 211, 101, 201, 108, 210, 480, 560, 20, 8));
        expect("one pixel", new int[]{5, 6, 1, 1},
                PopupPlacement.place(5, 6, 6, 7, 0, 0, 48, 48, 0, 0, 20, 8));
        expect("anchor far outside lower right", new int[]{28, 308, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, 2000, 2000, 2048, 2048, 320, 480, 12, 8));
        expect("anchor far outside upper left", new int[]{12, 36, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, -2048, -2048, -2000, -2000, 320, 480, 12, 8));
        expect("below preferred when full panel fits", new int[]{300, 620, 400, 200},
                PopupPlacement.place(0, 0, 1000, 1000, 480, 560, 520, 610, 400, 200, 10, 10));
        expect("tie prefers below", new int[]{300, 610, 400, 380},
                PopupPlacement.place(0, 0, 1000, 1000, 480, 400, 520, 600, 400, 900, 10, 10));
        expect("negative inputs normalize", new int[]{199, 150, 1, 1},
                PopupPlacement.place(0, 0, 400, 800, 176, 100, 224, 150, -30, -10, -20, -10));
        expect("landscape keyboard keeps original anchored behavior", new int[]{38, 34, 360, 39},
                PopupPlacement.place(0, 24, 640, 170, 196, 80, 240, 124, 360, 552, 10, 7));
        expect("landscape keyboard exposes scrollable controls", new int[]{38, 34, 360, 126},
                PopupPlacement.place(0, 24, 640, 170, 196, 80, 240, 124, 360, 552, 10, 7, 180));
        expect("fallback respects requested height", new int[]{38, 34, 360, 100},
                PopupPlacement.place(0, 24, 640, 170, 196, 80, 240, 124, 360, 100, 10, 7, 180));
        expect("fallback not needed on phone", new int[]{20, 108, 320, 480},
                PopupPlacement.place(0, 24, 360, 800, 156, 52, 204, 100, 320, 480, 12, 8, 180));
        expect("exact minimum keeps anchor", new int[]{38, 34, 360, 39},
                PopupPlacement.place(0, 24, 640, 170, 196, 80, 240, 124, 360, 552, 10, 7, 39));
        expect("zero minimum disables fallback", new int[]{38, 34, 360, 39},
                PopupPlacement.place(0, 24, 640, 170, 196, 80, 240, 124, 360, 552, 10, 7, 0));
        expect("fallback keeps one-pixel screen", new int[]{5, 6, 1, 1},
                PopupPlacement.place(5, 6, 6, 7, 0, 0, 48, 48, 0, 0, 20, 8, 180));
        expect("fallback screen offset", new int[]{438, 234, 360, 126},
                PopupPlacement.place(400, 224, 1040, 370, 596, 280, 640, 324, 360, 552, 10, 7, 180));
        invalid(0, 0, 0, 1);
        invalid(0, 0, 1, 0);
        invalid(20, 0, 10, 1);
        invalid(0, 20, 1, 10);

        // Randomized independent containment checks include off-screen/reversed anchors,
        // enormous spacing, tiny viewports, negative coordinates, and int-boundary screens.
        Random random = new Random(20260922L);
        for (int iteration = 0; iteration < 20000; iteration++) {
            int firstX = random.nextInt();
            int secondX = random.nextInt();
            int firstY = random.nextInt();
            int secondY = random.nextInt();
            if (firstX == secondX || firstY == secondY) continue;
            int left = Math.min(firstX, secondX);
            int right = Math.max(firstX, secondX);
            int top = Math.min(firstY, secondY);
            int bottom = Math.max(firstY, secondY);
            int width = random.nextInt();
            int height = random.nextInt();
            int margin = random.nextInt();
            int[] bounds = PopupPlacement.place(left, top, right, bottom,
                    random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt(),
                    width, height, margin, random.nextInt());
            long horizontalMargin = Math.min(Math.max(0L, margin), ((long) right - left - 1) / 2);
            long verticalMargin = Math.min(Math.max(0L, margin), ((long) bottom - top - 1) / 2);
            check(bounds[2] >= 1 && bounds[3] >= 1, "Empty popup");
            check(bounds[2] <= Math.max(1, width) && bounds[3] <= Math.max(1, height), "Popup exceeds request");
            check(bounds[0] >= left + horizontalMargin && bounds[1] >= top + verticalMargin, "Popup starts outside viewport");
            check((long) bounds[0] + bounds[2] <= right - horizontalMargin, "Popup exceeds right viewport edge");
            check((long) bounds[1] + bounds[3] <= bottom - verticalMargin, "Popup exceeds bottom viewport edge");
        }
        // Short windows exercise the usable-height fallback frequently, with both valid
        // and off-screen anchors. Expected containment is independent of side selection.
        int fallbackCases = 0;
        for (int iteration = 0; iteration < 20000; iteration++) {
            int left = random.nextInt(3001) - 1500;
            int top = random.nextInt(3001) - 1500;
            int right = left + 1 + random.nextInt(1600);
            int bottom = top + 1 + random.nextInt(800);
            int anchorLeft = left - 100 + random.nextInt(1800);
            int anchorTop = top - 100 + random.nextInt(1000);
            int anchorRight = anchorLeft + 48;
            int anchorBottom = anchorTop + 48;
            int width = random.nextInt(600) - 10;
            int height = random.nextInt(800) - 10;
            int margin = random.nextInt(80) - 10;
            int gap = random.nextInt(40) - 5;
            int minimum = random.nextInt(400) - 10;
            int[] original = PopupPlacement.place(left, top, right, bottom,
                    anchorLeft, anchorTop, anchorRight, anchorBottom, width, height, margin, gap);
            int[] bounds = PopupPlacement.place(left, top, right, bottom,
                    anchorLeft, anchorTop, anchorRight, anchorBottom, width, height, margin, gap, minimum);
            long horizontalMargin = Math.min(Math.max(0L, margin), ((long) right - left - 1) / 2);
            long verticalMargin = Math.min(Math.max(0L, margin), ((long) bottom - top - 1) / 2);
            check(bounds[2] >= 1 && bounds[3] >= 1, "Empty fallback popup");
            check(bounds[2] <= Math.max(1, width) && bounds[3] <= Math.max(1, height), "Fallback exceeds request");
            check(bounds[0] >= left + horizontalMargin && bounds[1] >= top + verticalMargin, "Fallback starts outside viewport");
            check((long) bounds[0] + bounds[2] <= right - horizontalMargin, "Fallback exceeds right edge");
            check((long) bounds[1] + bounds[3] <= bottom - verticalMargin, "Fallback exceeds bottom edge");
            check(bounds[0] == original[0] && bounds[2] == original[2], "Fallback changed horizontal geometry");
            if (original[3] >= minimum) {
                expect("usable panel stays anchored", original, bounds);
            } else {
                fallbackCases++;
                long fullHeight = (long) bottom - top - verticalMargin * 2;
                check(bounds[3] == Math.min(Math.max(1, height), fullHeight), "Fallback did not use available height");
                check(bounds[3] >= original[3], "Fallback made panel shorter");
            }
        }
        check(fallbackCases > 5000, "Insufficient short-window fallback coverage");
        System.out.println("PASS: phone/tablet, anchor edges, keyboard bounds, screen offsets, tiny windows, off-screen anchors, direction selection, invalid screens, 20000 containment cases, and 20000 usable-height fallback cases");
    }
}

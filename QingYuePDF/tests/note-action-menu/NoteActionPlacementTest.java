package cn.qingyue.pdf;

public final class NoteActionPlacementTest {
    public static void main(String[] args) {
        check(NoteActionPlacement.place(0, 24, 1080, 1600, 990, 120, 800, 500, 16), 264, 112, 1);
        check(NoteActionPlacement.place(300, 80, 660, 600, 590, 480, 328, 420, 8), 16, 50, 0);
        check(NoteActionPlacement.place(0, 0, 220, 320, 170, 48, 188, 260, 8), 16, 30, 0);
        int[] tiny = NoteActionPlacement.place(0, 0, 80, 100, 40, 30, 48, 60, 8);
        if (tiny[0] < 0 || tiny[0] + 48 > 80 || tiny[1] < 0 || tiny[1] + 60 > 100)
            throw new AssertionError("Tiny window overflow");
    }
    private static void check(int[] result, int x, int y, int anchored) {
        if (result[0] != x || result[1] != y || result[2] != anchored)
            throw new AssertionError(result[0] + "," + result[1] + "," + result[2]);
    }
}

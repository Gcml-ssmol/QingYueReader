package cn.qingyue.pdf;

/** Input ownership and pinch thresholds, independent of Android event dispatch. */
public final class TouchNavigation {
    private TouchNavigation() { }

    public static double resizedScroll(double scroll,double oldHeight,double oldScale,double newHeight,double newScale){
        return scroll+oldHeight/(2*oldScale)-newHeight/(2*newScale);
    }

    public static boolean safeContact(int tool, float major, float size, float density) {
        return (tool == 1 || tool == 3) && major <= 48 * density && size <= .45f;
    }

    public static final class Guard {
        private long lastPen = Long.MIN_VALUE / 2;
        private boolean blocked;
        public void pen(long time) { lastPen = time; blocked = true; }
        /** A pen or palm contaminates the whole touch sequence, including its remaining fingers. */
        public boolean allow(int action, long time, boolean hasPen, boolean safe) {
            if (hasPen) pen(time);
            else if (action == 0) blocked = !safe || time - lastPen < 280;
            if (!safe) blocked = true;
            return !blocked;
        }
    }

    public static final class Pinch {
        private final float slop, minimumSpan;
        private int first = -1, second = -1;
        private float originX, originY, originSpan, previousX, previousY, previousSpan;
        private boolean moving, scaling;
        public float oldX, oldY, x, y, scale = 1;
        public Pinch(float density) { slop = 8 * density; minimumSpan = 24 * density; }
        public void reset() { first = second = -1; moving = scaling = false; scale = 1; }
        public void begin(int a, int b, float ax, float ay, float bx, float by) {
            first = a; second = b; moving = scaling = false;
            originX = previousX = (ax + bx) / 2; originY = previousY = (ay + by) / 2;
            originSpan = previousSpan = (float)Math.hypot(bx - ax, by - ay);
        }
        /** Activation rebases without applying the accumulated slop as a visible jump. */
        public boolean move(int a, int b, float ax, float ay, float bx, float by, boolean tapPending) {
            if (!((a == first && b == second) || (a == second && b == first))) {
                begin(a, b, ax, ay, bx, by); return false;
            }
            float nextX = (ax + bx) / 2, nextY = (ay + by) / 2;
            float span = (float)Math.hypot(bx - ax, by - ay);
            if (tapPending) return false;
            if (span < minimumSpan || originSpan < minimumSpan) {
                begin(a, b, ax, ay, bx, by); return false;
            }
            boolean startsScale = !scaling && Math.abs(span - originSpan) > Math.max(slop, originSpan * .06f);
            boolean startsMove = !moving && Math.hypot(nextX - originX, nextY - originY) > slop;
            boolean wasActive = moving || scaling;
            if (startsScale) scaling = true;
            if (startsMove) moving = true;
            if (!moving && !scaling) return false;
            oldX = previousX; oldY = previousY; x = nextX; y = nextY;
            scale = scaling && !startsScale && previousSpan > 0 ? span / previousSpan : 1;
            previousX = nextX; previousY = nextY; previousSpan = span;
            return wasActive;
        }
    }

    /** Only unused outward movement at an edge can turn a page; writing never calls this. */
    public static final class PageDrag {
        private float outward;
        public void reset() { outward = 0; }
        public void add(float requested, float consumed) {
            float remainder = requested - consumed;
            if (Math.abs(consumed) > .5f || outward * remainder < 0) outward = 0;
            if (Math.abs(remainder) > .01f) outward += remainder;
        }
        public int turn(float totalMain, float totalCross, float threshold) {
            return Math.abs(outward) >= threshold && Math.abs(totalMain) > Math.abs(totalCross) * 1.4f
                    ? (outward < 0 ? 1 : -1) : 0;
        }
        public boolean canAddPage(boolean handTool,boolean pen,double relativeZoom,long duration,
                float totalMain,float totalCross,float threshold){
            return handTool&&!pen&&Math.abs(relativeZoom-1)<=.02&&duration>=250
                    &&turn(totalMain,totalCross,threshold*2)>0;
        }
    }
}

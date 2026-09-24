package cn.qingyue.pdf;

/** Docking leaves room for reading even at the smallest supported tablet width. */
public final class NavigationLayoutTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int width:new int[]{240,320,360,411,480,599})check(!ReaderSidebarLayout.canDock(width)&&ReaderSidebarLayout.width(width)==0,"narrow pane squeezed at "+width);
        for(int width:new int[]{600,720,800,1024,1280,1920}){int rail=ReaderSidebarLayout.width(width);check(rail>=112&&rail<=168,"rail out of bounds");check(width-rail>=480,"reading pane too narrow");check(rail<=width/4,"rail consumes over quarter of reading row");}
        check(ReaderSidebarLayout.width(800)==133&&ReaderSidebarLayout.width(1280)==168,"responsive width decisions");
        System.out.println("NavigationLayoutTest passed: narrow modal fallback, 112-168dp tablet rails and reading-width budget.");
    }
}

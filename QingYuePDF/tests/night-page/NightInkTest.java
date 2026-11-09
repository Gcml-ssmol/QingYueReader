package cn.qingyue.pdf;

public final class NightInkTest {
    public static void main(String[] args) {
        int paper=0xff202327;
        for(int original:new int[]{0xff000000,0xff0000ff,0xff400080,0xff102060,0xff701030}){
            int shown=NightInk.display(original,paper);
            check(NightInk.contrastOnPaper(shown,paper)>=4.5,"dark pen: "+Integer.toHexString(original));
            check((shown>>>24)==(original>>>24),"alpha changed");
            check(original!=shown,"dark pen not lifted");
        }
        check(NightInk.display(0xffe8e8e8,paper)==0xffe8e8e8,"light ink changed");
        int marker=0x55f6c343;
        check(NightInk.display(marker,paper)==marker,"transparent highlighter changed");
        check(NightInk.contrastOnPaper(marker,paper)<4.5,
                "highlight should be evaluated as a translucent overlay, not text");
        System.out.println("NightInkTest OK");
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}

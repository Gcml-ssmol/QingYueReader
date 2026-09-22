package cn.qingyue.pdf;

public class PageMathTest {
    public static void main(String[] args) {
        int[][] cases={{595,842},{842,595},{1,2000000000},{2000000000,1},{10000,10000},{100,10000}};
        for(int[] page:cases)for(int desired:new int[]{800,2200,10000}) {
            int[] d=PageMath.dimensions(page[0],page[1],desired);
            if(d[0]<1||d[1]<1||d[0]>8192||d[1]>8192||(long)d[0]*d[1]>6000000)
                throw new AssertionError("Unsafe allocation: "+d[0]+"x"+d[1]);
        }
        int[] normal=PageMath.dimensions(595,842,1200);
        if(normal[0]!=1200||Math.abs((double)normal[1]/normal[0]-842.0/595)>0.002)throw new AssertionError("Aspect ratio changed");
        try {PageMath.dimensions(0,10,800);throw new AssertionError("Invalid dimensions accepted");}
        catch(IllegalArgumentException expected){}
        System.out.println("PASS: bitmap bounds, extreme aspect ratios, normal aspect ratio, invalid dimensions");
    }
}

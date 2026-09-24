package cn.qingyue.pdf;

public final class PageInsertionUiTest {
    private static int assertions;
    public static void main(String[] args){
        // User's 12 / 365 example: new zero-based page 12 is displayed as page 13.
        equal(12,PageInsertionPosition.index(11,365,false));
        equal(11,PageInsertionPosition.index(11,365,true));
        equal("在第 12 页之后（第 12 / 13 页之间）",PageInsertionPosition.label(11,365,false));
        equal("在第 12 页之前（第 11 / 12 页之间）",PageInsertionPosition.label(11,365,true));
        equal(0,PageInsertionPosition.index(0,1,true));equal(1,PageInsertionPosition.index(0,1,false));
        equal("在第 1 页之前（成为新的首页）",PageInsertionPosition.label(0,1,true));
        equal("在第 365 页之后（文档末尾）",PageInsertionPosition.label(364,365,false));
        rejects(0,0);rejects(-1,365);rejects(365,365);rejects(0,Integer.MAX_VALUE);
        for(int count:new int[]{1,2,365,10000})for(int page=0;page<count;page++){
            int before=PageInsertionPosition.index(page,count,true),after=PageInsertionPosition.index(page,count,false);
            check(before>=0&&before<count&&after==before+1&&after<=count);
        }
        // A recycled row must reject the old request even when rebound to the same page.
        ThumbnailBinding binding=new ThumbnailBinding();long first=binding.bind(11,1);
        check(binding.accepts(11,1,first));check(!binding.accepts(12,1,first));check(!binding.accepts(11,2,first));
        long second=binding.bind(12,1);check(!binding.accepts(11,1,first));check(binding.accepts(12,1,second));
        long third=binding.bind(11,1);check(!binding.accepts(11,1,first));check(binding.accepts(11,1,third));
        binding.clear();check(!binding.accepts(11,1,third));
        long reopened=binding.bind(11,2);check(!binding.accepts(11,1,third));check(binding.accepts(11,2,reopened));
        check(ReaderSidebarLayout.canDock(800));check(!ReaderSidebarLayout.canDock(360));
        System.out.println("PASS: insertion positions, 365-page boundaries, recycled thumbnail identity and layout ("+assertions+" assertions)");
    }
    private static void rejects(int page,int count){try{PageInsertionPosition.index(page,count,false);throw new AssertionError("accepted invalid location");}catch(IllegalArgumentException expected){assertions++;}}
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("assertion "+assertions);}
    private static void equal(Object expected,Object actual){check(expected.equals(actual));}
}

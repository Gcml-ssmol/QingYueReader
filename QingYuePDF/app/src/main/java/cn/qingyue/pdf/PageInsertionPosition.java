package cn.qingyue.pdf;

/** Zero-based model insertion positions and explicit labels for an existing page. */
final class PageInsertionPosition {
    private PageInsertionPosition() {}
    static int index(int page,int pageCount,boolean before){
        if(pageCount<1||pageCount==Integer.MAX_VALUE||page<0||page>=pageCount)throw new IllegalArgumentException("页面已变化，请重新选择");
        return before?page:page+1;
    }
    static String label(int page,int pageCount,boolean before){
        int position=index(page,pageCount,before);
        if(position==0)return "在第 1 页之前（成为新的首页）";
        if(position==pageCount)return "在第 "+pageCount+" 页之后（文档末尾）";
        return "在第 "+(page+1)+" 页"+(before?"之前":"之后")+"（第 "+position+" / "+(position+1)+" 页之间）";
    }
}

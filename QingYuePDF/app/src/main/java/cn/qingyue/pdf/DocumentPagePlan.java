package cn.qingyue.pdf;

import java.io.IOException;

/** Pure page geometry and bounded output policy shared by importer presets. */
final class DocumentPagePlan {
    static final int MAX_PAGES=400;
    final int width,height,left,top,bottom;
    final float textSize,lineExtra;
    private DocumentPagePlan(int width,int height,int left,int top,int bottom,float textSize,float lineExtra){
        this.width=width;this.height=height;this.left=left;this.top=top;this.bottom=bottom;
        this.textSize=textSize;this.lineExtra=lineExtra;
    }
    static DocumentPagePlan create(boolean phone,int fontIndex){
        if(fontIndex<0||fontIndex>2)throw new IllegalArgumentException("font");
        if(phone)return new DocumentPagePlan(360,640,22,55,594,
                fontIndex==0?15f:fontIndex==1?16.5f:18f,4f);
        return new DocumentPagePlan(595,842,44,62,794,
                fontIndex==0?12.5f:fontIndex==1?14.5f:17f,3f);
    }
    static void requirePage(int nextPage)throws IOException{
        if(nextPage>MAX_PAGES)throw new IOException("重排后超过 400 页，请拆分文档或先导出为 PDF");
    }
}

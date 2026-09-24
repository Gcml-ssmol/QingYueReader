package cn.qingyue.pdf;

/** Labels and navigation availability for both paper notes and legacy multi-canvas notes. */
final class NoteEditorPresentation {
    private NoteEditorPresentation() { }
    static boolean hasPageNavigation(NoteDocument document){return !document.infinite()||document.pageCount()>1;}
    static String pagePosition(NoteDocument document){
        if(!document.infinite())return (document.currentPage()+1)+"/"+document.pageCount();
        return document.pageCount()>1?"画布 "+(document.currentPage()+1)+"/"+document.pageCount():"无限草稿";
    }
    static String pdfScope(NoteDocument document){
        if(!document.infinite())return "导出全部 "+document.pageCount()+" 页";
        return document.pageCount()>1?"导出全部 "+document.pageCount()+" 张独立画布":"导出当前画布内容";
    }
}


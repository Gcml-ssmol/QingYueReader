package cn.qingyue.pdf;

public final class BasicNoteExportBoundsTest {
    public static void main(String[] args) throws Exception {
        check(NoteDocument.create("blank", "A4"), 0, 0);
        NoteDocument empty = NoteDocument.create("grid", "infinite");
        BasicNoteExportBounds page = BasicNoteExportBounds.forDocument(empty);
        if (page.width < 590 || page.height < 840) throw new AssertionError("empty infinite page");
        NoteDocument ink = NoteDocument.create("blank", "infinite");
        ink.add(new NoteDocument.Item(ink.newId(), NoteDocument.STROKE, 0xff000000, 4,
                new double[]{-100, -50, 200, 300}, ""));
        page = BasicNoteExportBounds.forDocument(ink);
        if (page.left > -125 || page.top > -75 || page.width < 350 || page.height < 400)
            throw new AssertionError("ink bounds");
        check(ink, -100, -50);
        NoteDocument text = NoteDocument.create("blank", "infinite");
        text.add(new NoteDocument.Item(text.newId(), NoteDocument.TEXT, 0xff000000, 24,
                new double[]{-80, -30, 120, 90}, "", "中文 English\n第二行"));
        page = BasicNoteExportBounds.forDocument(text);
        if (page.left != -104 || page.top != -54 || page.width != 248 || page.height != 168)
            throw new AssertionError("text export bounds should use its box without stroke padding");
        NoteDocument far = NoteDocument.create("blank", "infinite");
        far.add(new NoteDocument.Item(far.newId(), NoteDocument.STROKE, 0xff000000, 2,
                new double[]{0, 0, 20000, 0}, ""));
        try { BasicNoteExportBounds.forDocument(far); throw new AssertionError("oversized page accepted"); }
        catch (java.io.IOException expected) { }
        NoteDocument pages=NoteDocument.create("blank","infinite");pages.add(new NoteDocument.Item(pages.newId(),NoteDocument.STROKE,0xff000000,2,new double[]{-100,0,-50,20},""));pages.addPage();pages.add(new NoteDocument.Item(pages.newId(),NoteDocument.STROKE,0xff000000,2,new double[]{700,900,750,950},""));BasicNoteExportBounds first=BasicNoteExportBounds.forItems(pages,pages.pageItems(0)),second=BasicNoteExportBounds.forItems(pages,pages.pageItems(1));if(first.left>=0||second.left<650||second.top<850)throw new AssertionError("multi-page PDF needs per-page bounds");
    }

    private static void check(NoteDocument document, double x, double y) throws Exception {
        BasicNoteExportBounds page = BasicNoteExportBounds.forDocument(document);
        if (page.left > x || page.top > y || page.pixelsWide > 4096 || page.pixelsHigh > 4096 ||
                (long)page.pixelsWide * page.pixelsHigh > 16_000_000L)
            throw new AssertionError("invalid export limits");
    }
}

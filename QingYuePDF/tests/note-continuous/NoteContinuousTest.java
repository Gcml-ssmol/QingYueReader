package cn.qingyue.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Run with production NoteDocument and NotePageLayout, without Android mocks. */
public final class NoteContinuousTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static NoteDocument.Item stroke(NoteDocument doc, double y) {
        return new NoteDocument.Item(doc.newId(), NoteDocument.STROKE, 0xff000000, 2,
                new double[]{10, y, 20, y + 10}, "");
    }

    public static void main(String[] args) throws Exception {
        NoteDocument doc = NoteDocument.create("blank", "A4");
        NotePageLayout layout = new NotePageLayout(doc.width, doc.height, 24);
        doc.add(stroke(doc, 30));
        doc.addPage();
        doc.add(stroke(doc, 40));
        check(doc.pageItems(0).size() == 1 && doc.pageItems(1).size() == 1, "items must stay on their own page");
        check(layout.hit(20, doc.height + 12, 2) == -1, "the paper gap must not accept ink");
        check(layout.hit(20, layout.top(1) + 40, 2) == 1, "touch on the next page must resolve to that page");
        check(layout.hit(20, layout.top(1) + 40, 2) != layout.nearest(doc.height / 2, 2),
                "pen page must follow its contact, not the viewport centre");
        check(layout.firstVisible(doc.height - 80, 2) == 0 && layout.lastVisible(layout.top(1) + 80, 2) == 1,
                "one viewport must include portions of adjacent pages");
        double page500Centre = layout.top(499) + doc.height / 2;
        check(Math.abs(doc.height / 2 - layout.localY(499, page500Centre)) < 1e-8,
                "far pages must compare cached ink against the local viewport centre");
        double start = doc.height - 50;
        double priorScreenTop = layout.top(1) - start;
        for (int n = 1; n <= 100; n++) {
            double centre = start + n * 2;
            double screenTop = layout.top(1) - centre;
            check(Math.abs(screenTop - (priorScreenTop - 2)) < 1e-8,
                    "the next page must move smoothly as the viewport crosses the gap");
            priorScreenTop = screenTop;
            check(layout.nearest(centre, 2) >= 0, "scroll must have a selected page");
        }
        doc.selectPage(0);
        doc.add(stroke(doc, 60));
        doc.selectPage(1);
        check(!doc.canUndo() && doc.items().size() == 1,
                "switching pages must not expose another page's undo action");
        doc.selectPage(0);
        check(!doc.canUndo() && doc.items().size() == 2,
                "revisiting a page must preserve ink without cross-page history");
        doc.add(stroke(doc, 80));
        check(doc.undo() && doc.items().size() == 2, "undo must affect only the active page");
        doc.redo();
        check(doc.items().size() == 3, "redo must remain local to the page");
        doc.insertPage(1);
        check(doc.pageCount() == 3 && doc.items().isEmpty(), "inserted paper must start empty");
        doc.movePage(1, 2);
        check(doc.currentPage() == 2, "reordered current paper must stay selected");
        doc.centerY = 160; // v3 stores page-local view centres.
        doc.viewportSaved = true;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NoteDocument.write(bytes, doc);
        NoteDocument reopened = NoteDocument.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(reopened.pageCount() == 3 && reopened.currentPage() == 2, "page order and selection must survive reopen");
        check(reopened.pageItems(0).size() == 3 && reopened.pageItems(1).size() == 1 && reopened.pageItems(2).isEmpty(),
                "each page's ink must survive insertion, reorder and reopen");
        check(reopened.centerY == 160 && layout.top(2) + reopened.centerY - layout.top(2) == 160,
                "saved v3 view centre must remain page local");
        System.out.println("note-continuous: OK");
    }
}

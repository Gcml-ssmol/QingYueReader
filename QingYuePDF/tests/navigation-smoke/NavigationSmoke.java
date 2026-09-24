package cn.qingyue.pdf;

import android.content.Context;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSInteger;
import com.tom_roush.pdfbox.cos.COSString;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary;
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange;
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import java.util.Collections;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Desktop PDFBox parser regression, with a hard timeout for corrupt outline traversal. */
public final class NavigationSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static PdfNavigationIndex boundedLoad(final File file) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "navigation-smoke-loader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<PdfNavigationIndex> result = worker.submit(new Callable<PdfNavigationIndex>() {
                public PdfNavigationIndex call() throws Exception {
                    return PdfNavigationIndex.load(new Context(), file);
                }
            });
            return result.get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        File directory = new File(System.getProperty("smoke.root"));
        File plain = new File(directory, "plain.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(plain);
        }
        PdfNavigationIndex basic = boundedLoad(plain);
        check(basic.outline.isEmpty(), "PDF without outline has entries");
        check(basic.pageLabels.length == 2 && "1".equals(basic.label(0)) && "2".equals(basic.label(1)), "Default labels");

        File labeled = new File(directory, "labeled.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(), second = new PDPage();
            document.addPage(first);
            document.addPage(second);
            PDPageLabels labels = new PDPageLabels(document);
            PDPageLabelRange range = new PDPageLabelRange();
            range.setPrefix("A-");
            range.setStyle(PDPageLabelRange.STYLE_DECIMAL);
            labels.setLabelItem(0, range);
            document.getDocumentCatalog().setPageLabels(labels);
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem chapter = new PDOutlineItem();
            chapter.setTitle("Chapter");
            chapter.setDestination(second);
            outline.addLast(chapter);
            document.save(labeled);
        }
        PdfNavigationIndex named = boundedLoad(labeled);
        check(named.pageLabels.length == 2 && "A-1".equals(named.label(0)) && "A-2".equals(named.label(1)), "Custom labels");
        check(named.outline.size() == 1 && "Chapter".equals(named.outline.get(0).title)
                && named.outline.get(0).page == 1 && "A-2".equals(named.outline.get(0).label), "Outline destination and label");

        File advanced = new File(directory, "named-goto-nested.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(), second = new PDPage(), third = new PDPage();
            document.addPage(first); document.addPage(second); document.addPage(third);
            PDPageFitDestination target = new PDPageFitDestination(); target.setPage(third);
            PDDestinationNameTreeNode destinations = new PDDestinationNameTreeNode();
            destinations.setNames(Collections.singletonMap("chapter-three", target));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setDests(destinations); document.getDocumentCatalog().setNames(names);
            PDDocumentOutline outline = new PDDocumentOutline(); document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem heading = new PDOutlineItem(); heading.setTitle("Parent without destination"); outline.addLast(heading);
            PDOutlineItem nested = new PDOutlineItem(); nested.setTitle("Named nested");
            nested.setDestination(new PDNamedDestination("chapter-three")); heading.addLast(nested);
            PDOutlineItem actionItem = new PDOutlineItem(); actionItem.setTitle("GoTo second");
            PDPageFitDestination secondTarget = new PDPageFitDestination(); secondTarget.setPage(second);
            PDActionGoTo action = new PDActionGoTo(); action.setDestination(secondTarget);
            actionItem.setAction(action); outline.addLast(actionItem);
            document.save(advanced);
        }
        PdfNavigationIndex advancedIndex = boundedLoad(advanced);
        check(advancedIndex.outline.size() == 2, "Named and GoTo entries should resolve while parent without destination is skipped");
        check("Named nested".equals(advancedIndex.outline.get(0).title) && advancedIndex.outline.get(0).page == 2
                && advancedIndex.outline.get(0).depth == 1, "Named nested destination and depth");
        check("GoTo second".equals(advancedIndex.outline.get(1).title) && advancedIndex.outline.get(1).page == 1
                && advancedIndex.outline.get(1).depth == 0, "GoTo action destination");

        File damaged = new File(directory, "damaged.pdf");
        try (FileOutputStream output = new FileOutputStream(damaged)) {
            output.write("%PDF-1.7\ntruncated".getBytes("US-ASCII"));
        }
        try {
            boundedLoad(damaged);
            throw new AssertionError("Damaged PDF was accepted");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof java.io.IOException || expected.getCause() instanceof RuntimeException,
                    "Unexpected corrupt-file failure: " + expected.getCause());
        }

        File malformedLabels = new File(directory, "malformed-labels.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page=new PDPage();document.addPage(page);
            PDDocumentOutline outline=new PDDocumentOutline();document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem entry=new PDOutlineItem();entry.setTitle("Usable chapter");entry.setDestination(page);outline.addLast(entry);
            COSArray entries = new COSArray();
            entries.add(COSInteger.ZERO);
            entries.add(new COSString("a label range should be a dictionary"));
            COSDictionary labelsDictionary = new COSDictionary();
            labelsDictionary.setItem(COSName.NUMS, entries);
            document.getDocumentCatalog().getCOSObject().setItem(COSName.PAGE_LABELS, labelsDictionary);
            document.save(malformedLabels);
        }
        PdfNavigationIndex recoveredLabels=boundedLoad(malformedLabels);
        check(recoveredLabels.outline.size()==1&&"Usable chapter".equals(recoveredLabels.outline.get(0).title)
                &&"1".equals(recoveredLabels.label(0)),"Malformed labels hid valid outline");

        // PDFBox wraps a bad /Nums value in IOException itself; a bad /PageLabels
        // value reaches the catalog's unchecked COSDictionary cast.
        File malformedRootLabels = new File(directory, "malformed-root-labels.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.PAGE_LABELS, new COSString("not a number tree"));
            document.save(malformedRootLabels);
        }
        PdfNavigationIndex recoveredRootLabels=boundedLoad(malformedRootLabels);
        check("1".equals(recoveredRootLabels.label(0)),"Malformed label root did not fall back to page number");

        File cyclic = new File(directory, "cyclic.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem chapter = new PDOutlineItem();
            chapter.setTitle("Cycle");
            chapter.setDestination(page);
            outline.addLast(chapter);
            // Malformed child link points back to the same node.
            chapter.getCOSObject().setItem(COSName.FIRST, chapter.getCOSObject());
            chapter.getCOSObject().setItem(COSName.LAST, chapter.getCOSObject());
            document.save(cyclic);
        }
        PdfNavigationIndex cycle = boundedLoad(cyclic);
        check(cycle.outline.size() <= PdfNavigationIndex.MAX_OUTLINE_ITEMS, "Cyclic outline exceeded item cap");
        check(cycle.pageLabels.length == 1, "Cyclic outline lost page labels");
        System.out.println("NavigationSmoke passed: named and GoTo targets, nested outline, broken-label fallback, corrupt PDF, cyclic outline; all loads bounded to 10 seconds.");
    }
}

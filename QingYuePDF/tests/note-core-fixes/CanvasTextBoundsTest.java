package cn.qingyue.pdf;

import android.content.Context;

/** Production canvas/layout path with deterministic desktop Android layout shims. */
public final class CanvasTextBoundsTest {
    static int assertions;
    static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    static final class Host implements NoteCanvasView.Host {
        int errors,changes;String last="";
        public void error(String message){errors++;last=message;}
        public void changed(){changes++;}
    }
    static String lines(int count){StringBuilder s=new StringBuilder();for(int i=0;i<count;i++){if(i>0)s.append('\n');s.append("一行长文本");}return s.toString();}
    public static void main(String[] args){
        NoteDocument finite=NoteDocument.create("blank","A5");Host host=new Host();
        NoteCanvasView canvas=new NoteCanvasView(new Context(),finite,"note",host);canvas.layout(0,0,800,600);
        check(!canvas.putText(lines(50),20,0xff123456),"50 lines should be refused on A5");
        check(host.errors==1&&host.last.contains("超出"),"oversize rejection has clear host error");
        check(finite.items().isEmpty()&&host.changes==0,"failed new text leaves page unchanged");
        check(canvas.putText("原文",20,0xff123456),"short text accepted");
        NoteDocument.Item original=finite.items().get(0);long id=original.id;
        check(!canvas.putText(lines(50),20,0xff123456),"oversize edit refused");
        check(finite.find(id)==original&&finite.items().size()==1,"failed edit preserves original object");
        check(canvas.selectText(id),"text selectable by ID for restored draft");
        canvas.scaleSelection(4);NoteDocument.Item scaled=finite.find(id);
        check(scaled.bottom()<=finite.height+1e-6&&scaled.right()<=finite.width+1e-6,"text scaling remains on page");
        NoteDocument.Item legacy=new NoteDocument.Item(finite.newId(),NoteDocument.TEXT,0xff000000,20,
                new double[]{0,0,300,finite.height+500},"",lines(50));finite.add(legacy);
        check(canvas.selectText(legacy.id),"legacy oversized item remains editable");
        check(canvas.putText("修正",20,0xff000000),"short edit repairs old overflow");
        check(finite.find(legacy.id).bottom()<=finite.height,"repaired item fits paper");
        NoteDocument.Item wideLegacy=new NoteDocument.Item(finite.newId(),NoteDocument.TEXT,0xff000000,20,
                new double[]{0,0,finite.width+100,50},"","旧版过宽文本");finite.add(wideLegacy);
        check(canvas.selectText(wideLegacy.id),"old wide item remains editable");
        check(canvas.putText("修正宽度",20,0xff000000),"short edit repairs old width overflow");
        check(finite.find(wideLegacy.id).right()<=finite.width,"repaired width fits paper");
        NoteDocument infinite=NoteDocument.create("blank","infinite");Host unboundedHost=new Host();
        NoteCanvasView unbounded=new NoteCanvasView(new Context(),infinite,"infinite",unboundedHost);unbounded.layout(0,0,800,600);
        check(unbounded.putText(lines(50),20,0xff123456),"infinite paper accepts same text");
        check(infinite.items().get(0).bottom()-infinite.items().get(0).top()>finite.height,"infinite text keeps full layout");
        System.out.println("CanvasTextBoundsTest passed: "+assertions+" assertions; production canvas/layout with deterministic Android text shim.");
    }
}

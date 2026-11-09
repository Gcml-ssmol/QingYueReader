package cn.qingyue.pdf;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Calls production View dispatch and drawing. Android dependencies record commands only. */
public final class NoteViewQaTest {
    static int assertions;static long time=10000;
    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    static void close(double actual,double expected,double tolerance,String message){check(Math.abs(actual-expected)<=tolerance,message+" actual="+actual+" expected="+expected);}
    static final class Host implements NoteCanvasView.Host {
        int changes,pages,settles;public void changed(){changes++;}public void error(String s){throw new AssertionError(s);}
        public void viewportPageChanged(int page){pages++;}public void viewportSettled(){settles++;}
    }
    static final class Fixture {
        final Context context=new Context();final NoteDocument doc;final Host host=new Host();final NoteCanvasView view;
        Fixture(int pages){this("A4",pages);}Fixture(String size,int pages){doc=NoteDocument.create("blank",size);while(doc.pageCount()<pages)doc.addPage();doc.selectPage(0);view=new NoteCanvasView(context,doc,"qa",host);view.layout(0,0,800,600);view.setTwoFingerUndoEnabled(false);}
    }
    static MotionEvent single(int action,int tool,float x,float y){return new MotionEvent(action,0,time+=40,new int[]{3},new int[]{tool},new float[]{x},new float[]{y});}
    static void stroke(Fixture f,float x,float y,float xx,float yy){time+=400;f.view.onTouchEvent(single(0,2,x,y));f.view.onTouchEvent(single(2,2,xx,yy));f.view.onTouchEvent(single(1,2,xx,yy));}
    static void drag(Fixture f,float x,float y,float xx,float yy){time+=400;f.view.onTouchEvent(single(0,1,x,y));f.view.onTouchEvent(single(2,1,x+(xx-x)*.05f,y+(yy-y)*.05f));f.view.onTouchEvent(single(2,1,xx,yy));f.view.onTouchEvent(single(1,1,xx,yy));}
    static double field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.getDouble(o);}
    static float screenY(Fixture f,int page,double local)throws Exception{return (float)(300+(page*(f.doc.height+24)+local-field(f.view,"cy"))*field(f.view,"zoom"));}
    static void pinch(Fixture f,float y)throws Exception {
        time+=400;f.view.onTouchEvent(single(0,1,300,y));
        f.view.onTouchEvent(new MotionEvent(5,1,time+=40,new int[]{3,4},new int[]{1,1},new float[]{300,500},new float[]{y,y}));
        f.view.onTouchEvent(new MotionEvent(2,0,time+=40,new int[]{3,4},new int[]{1,1},new float[]{280,520},new float[]{y,y}));
        f.view.onTouchEvent(new MotionEvent(2,0,time+=40,new int[]{3,4},new int[]{1,1},new float[]{260,540},new float[]{y,y}));
        f.view.onTouchEvent(new MotionEvent(6,1,time+=40,new int[]{3,4},new int[]{1,1},new float[]{260,540},new float[]{y,y}));
        f.view.onTouchEvent(single(1,1,260,y));
    }
    static void pinchAndRestore()throws Exception{
        Fixture f=new Fixture(3);f.view.selectPage(1);f.view.setTool("hand",0xff000000,2,true);
        double cy=field(f.view,"cy"),zoom=field(f.view,"zoom");pinch(f,300);
        check(f.doc.currentPage()==1&&f.view.visiblePage()==1,"pinch on second page stays on second page");
        close(field(f.view,"cy"),cy,.01,"pinch keeps global center");check(field(f.view,"zoom")>zoom,"real pinch still zooms");
        drag(f,400,550,400,-850);check(f.doc.currentPage()>=1,"continuous drag advances through pages");
        f.view.selectPage(0);check(f.view.visiblePage()==0,"explicit first-page jump returns first page");
        f.view.selectPage(1);check(f.view.visiblePage()==1,"explicit second-page jump returns second page");
        NoteDocument copy=f.view.snapshot();NoteCanvasView reopened=new NoteCanvasView(f.context,copy,"qa",new Host());reopened.layout(0,0,800,600);
        check(reopened.visiblePage()==1,"snapshot restores visible page");close(field(reopened,"cy"),field(f.view,"cy"),.001,"snapshot restores global center");
        double absolute=field(f.view,"zoom");f.view.setHorizontalPages(true);
        close(field(f.view,"zoom"),absolute,.001,"mode switch preserves actual scale");
        double expected=Math.min((800-32)/f.doc.width,(600-32)/f.doc.height);
        close(field(f.view,"baseZoom"),expected,.001,"horizontal mode recalculates fit scale");
        f.view.setHorizontalPages(false);check(f.view.visiblePage()==1,"return to vertical preserves selected page");
    }
    static void boundaryEditing()throws Exception {
        Fixture f=new Fixture(2);f.view.setTool("hand",0xff000000,2,true);
        drag(f,400,550,400,-300); // reveal the join while page 0 is still visible
        double join=screenY(f,1,0);check(join>0&&join<600,"fixture displays page join");
        f.view.setTool("pen",0xff123456,4,true);float y=(float)join+40;
        stroke(f,350,y,420,y+25);
        check(f.doc.pageItems(0).isEmpty(),"writing on lower visible page does not touch prior page");
        check(f.doc.pageItems(1).size()==1,"writing on lower visible page belongs to that page");
        NoteDocument.Item ink=f.doc.pageItems(1).get(0);check(ink.top()>=0&&ink.bottom()<100,"stored ink coordinates remain page-local");
        int n=f.doc.pageItems(1).size();float gap=(float)join-10;
        stroke(f,350,gap,430,y+20);check(f.doc.pageItems(1).size()==n,"stroke starting in gap is rejected");
        // A stroke crossing the join remains clamped to its originating page.
        f.view.selectPage(0);f.view.setTool("hand",0,2,true);drag(f,400,550,400,-300);
        float end=screenY(f,0,f.doc.height);
        f.view.setTool("pen",0xff112233,4,true);stroke(f,350,end-25,350,end+80);
        check(f.doc.pageItems(0).size()==1,"cross-page stroke stays on initial page");
        check(f.doc.pageItems(0).get(0).bottom()<=f.doc.height,"cross-page stroke is bounded by paper");
        check(f.doc.pageItems(1).size()==n,"cross-page stroke does not write neighbour");
        f.view.undo();check(f.doc.pageItems(0).isEmpty(),"undo removes the originating page stroke");
        check(f.doc.pageItems(1).size()==n,"undo never changes neighbour");
        f.view.selectPage(1);f.view.setTool("eraser",0,80,true);
        // SelectPage puts current page top at the viewport top.
        float ey=screenY(f,1,ink.top()+5);stroke(f,350,ey,420,ey+20);
        check(f.doc.pageItems(0).isEmpty(),"eraser does not modify prior page");
        check(f.doc.pageItems(1).isEmpty(),"eraser touches local lower-page stroke");
        f.view.undo();check(f.doc.pageItems(1).size()==1,"eraser undo restores correct page");
        f.view.putText("second-page",16,0xff445566);check(f.doc.pageItems(1).size()==2,"text insertion uses active visible page");
        NoteDocument.Item text=f.doc.items().get(1);check(text.top()>=0&&text.bottom()<=f.doc.height,"text remains local to current page");
        f.view.insertImage("picture.png",400,300);check(f.doc.pageItems(1).size()==3,"image inserted on current page");
        NoteDocument.Item image=f.doc.items().get(2);check(image.top()>=0&&image.bottom()<=f.doc.height,"image remains local to current page");
        f.view.deleteSelected();check(f.doc.pageItems(1).size()==2,"selected image deletes from correct page");f.view.undo();check(f.doc.pageItems(1).size()==3,"image undo restores correct page");
    }
    static void selectionAndAppend()throws Exception {
        for(int type:new int[]{NoteDocument.TEXT,NoteDocument.IMAGE}){
            Fixture f=new Fixture(2);
            f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.STROKE,0xff123456,3,new double[]{100,100,200,100},""));
            f.doc.selectPage(1);
            long id=f.doc.newId();
            f.doc.add(new NoteDocument.Item(id,type,0xff334455,16,new double[]{100,100,300,200},type==NoteDocument.IMAGE?"image.png":"",type==NoteDocument.TEXT?"text":""));
            f.doc.selectPage(0);f.view.setTool("hand",0,2,true);drag(f,400,550,400,-300);
            float x=(float)(400+(200-field(f.view,"cx"))*field(f.view,"zoom")),y=screenY(f,1,150);
            f.view.setTool("select",0,2,true);time+=400;
            f.view.onTouchEvent(single(0,2,x,y));f.view.onTouchEvent(single(2,2,x,-100));f.view.onTouchEvent(single(1,2,x,-100));
            check(f.doc.currentPage()==1,"selection on lower visible page changes edit target");
            check(f.doc.pageItems(0).size()==1,"selection drag never changes neighbouring items");
            check(f.doc.pageItems(1).size()==1&&f.doc.find(id)!=null,"cross-boundary selection remains on original page");
            close(f.doc.find(id).top(),0,.001,"selection clamps to its own page top");
            f.view.undo();close(f.doc.find(id).top(),100,.001,"selection drag undo restores local bounds");
        }
        Fixture f=new Fixture(2);f.view.setZoomPercent(180);double zoom=field(f.view,"zoom"),cx=field(f.view,"cx");
        f.view.addPage();check(f.doc.currentPage()==2&&f.doc.pageCount()==3,"append selects new connected page");
        close(field(f.view,"zoom"),zoom,.001,"append preserves scale");close(field(f.view,"cx"),cx,.001,"append preserves horizontal anchor");
        f.view.insertPage(1);check(f.doc.currentPage()==1&&f.doc.pageCount()==4,"insert selects requested page");
        close(field(f.view,"zoom"),zoom,.001,"insert preserves scale");
        f.view.setTool("hand",0,2,true);f.view.setZoomPercent(100);
        drag(f,400,550,400,-900);check(f.host.settles>0,"completed scroll emits settlement");
        f.view.save();NoteDocument snapshot=f.view.snapshot();NoteCanvasView restored=new NoteCanvasView(f.context,snapshot,"qa",new Host());restored.layout(0,0,800,600);
        check(restored.visiblePage()==f.view.visiblePage(),"settled viewport restores same page");
    }
    static void drawingIsolation()throws Exception {
        Fixture f=new Fixture(2);double y=f.doc.height-50;
        f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.TEXT,0xff111122,16,new double[]{100,y,300,y+30},"","upper-page"));
        f.doc.selectPage(1);f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.TEXT,0xff223344,16,new double[]{100,30,300,60},"","lower-page"));f.doc.selectPage(0);
        f.view.setTool("hand",0,2,true);drag(f,400,550,400,-300);
        Canvas canvas=new Canvas();f.view.draw(canvas);
        List<Canvas.Command> text=new ArrayList<>();for(Canvas.Command c:canvas.commands)if(c.kind.equals("text"))text.add(c);
        check(text.size()==2,"onDraw includes both visible pages");
        check(text.get(0).text.equals("upper-page")&&text.get(1).text.equals("lower-page"),"draw order preserves page order");
        close(text.get(1).y-text.get(0).y,104*field(f.view,"zoom"),.02,"text world-to-screen includes page offset");
        f.view.selectPage(0);canvas=new Canvas();f.view.draw(canvas);int offscreen=0;for(Canvas.Command c:canvas.commands)if(c.text.equals("lower-page"))offscreen++;
        check(offscreen==0,"offscreen page items are not traversed/drawn");
    }
    static void palmAndModes()throws Exception {
        Fixture f=new Fixture(3);f.view.selectPage(1);double before=field(f.view,"cy"),zoom=field(f.view,"zoom");
        time+=400;f.view.onTouchEvent(single(0,2,350,200));
        f.view.onTouchEvent(new MotionEvent(5,1,time+=40,new int[]{3,4},new int[]{2,1},new float[]{350,500},new float[]{200,400}));
        f.view.onTouchEvent(new MotionEvent(2,0,time+=40,new int[]{3,4},new int[]{2,1},new float[]{370,700},new float[]{220,550}));
        f.view.onTouchEvent(new MotionEvent(6,0,time+=40,new int[]{3,4},new int[]{2,1},new float[]{370,700},new float[]{220,550}));
        f.view.onTouchEvent(new MotionEvent(2,0,time+=40,new int[]{4},new int[]{1},new float[]{100},new float[]{100}));
        f.view.onTouchEvent(new MotionEvent(1,0,time+=40,new int[]{4},new int[]{1},new float[]{100},new float[]{100}));
        close(field(f.view,"cy"),before,.001,"pen plus palm cannot pan");close(field(f.view,"zoom"),zoom,.001,"pen plus palm cannot zoom");
        check(f.doc.pageItems(1).size()==1,"pen keeps writing during mixed input");check(f.doc.pageCount()==3,"writing never adds pages");
        f.view.setHorizontalPages(true);f.view.fit();f.view.setTool("hand",0,2,true);
        drag(f,700,300,300,300);check(f.doc.currentPage()==2,"horizontal edge flip remains functional");
        Fixture infinite=new Fixture("infinite",1);infinite.view.setTool("hand",0,2,true);double old=field(infinite.view,"cy");drag(infinite,400,500,400,100);
        check(field(infinite.view,"cy")>old,"infinite canvas still pans");stroke(infinite,100,100,150,150);check(infinite.doc.pageCount()==1,"infinite pan does not create pages");
        f.view.dispose();int count=f.doc.items().size();check(!f.view.onTouchEvent(single(0,2,100,100)),"disposed view rejects events");check(f.doc.items().size()==count,"disposed event cannot mutate");
    }
    static void walk(View v,List<View> rows){if(v.getContentDescription()!=null)rows.add(v);if(v instanceof ViewGroup)for(View c:((ViewGroup)v).children)walk(c,rows);}
    static void menu() {
        Context context=new Context();View anchor=new View(context);anchor.layout(0,0,40,40);anchor.screenY=20;
        String[] labels={"查看来源","导出 PDF","导出 PNG","只读浏览","缩放与定位","页面操作","笔记设置"};
        for(int index=0;index<labels.length;index++){
            final int[] calls=new int[labels.length];final Dialog[] holder=new Dialog[1];NoteActionMenu.Action[] actions=new NoteActionMenu.Action[labels.length];
            for(int i=0;i<labels.length;i++){final int n=i;actions[i]=new NoteActionMenu.Action("●",labels[i],
                    i==2?"导出当前第 2 页":i==5?"插入、复制、清空、删除与调序":"操作",()->{check(!holder[0].shown,"menu dismisses before action");calls[n]++;});}
            holder[0]=NoteActionMenu.showActions(context,anchor,"笔记操作",actions);
            check(holder[0].content instanceof ScrollView,"menu has scroll container");
            List<View> rows=new ArrayList<>();walk(holder[0].content,rows);check(rows.size()==labels.length,"menu exposes production operations");
            for(int i=0;i<labels.length;i++)check(rows.get(i).getContentDescription().toString().startsWith(labels[i]),"operation order and label "+i);
            check(rows.get(2).getContentDescription().toString().contains("第 2 页"),"PNG describes current page");
            rows.get(index).performClick();for(int i=0;i<labels.length;i++)check(calls[i]==(i==index?1:0),"callback mapping "+index+"/"+i);
        }
        anchor.visible.set(0,0,220,180);context.getResources().getDisplayMetrics().scaledDensity=2;
        NoteActionMenu.Action[] actions=new NoteActionMenu.Action[labels.length];for(int i=0;i<labels.length;i++)actions[i]=new NoteActionMenu.Action("●",labels[i],"操作",()->{});
        Dialog small=NoteActionMenu.showActions(context,anchor,"笔记操作",actions);
        check(small.getWindow().width<=188,"menu fits narrow split window");check(small.getWindow().height<=148,"menu fits short/keyboard window");
        ScrollView scroll=(ScrollView)small.content;check(scroll.children.get(0).getMeasuredHeight()>small.getWindow().height,"large-font menu content remains scrollable");
        anchor.visible.set(0,0,0,0);Dialog fallback=NoteActionMenu.showActions(context,anchor,"笔记操作",actions);
        check(fallback.getWindow().width>0&&fallback.getWindow().width<=360,"zero-frame fallback uses display size");
    }
    static void textDraftAndPageTarget()throws Exception {
        Fixture f=new Fixture(3);f.view.selectPage(1);
        long id=f.doc.newId();f.doc.add(new NoteDocument.Item(id,NoteDocument.TEXT,0xff2464d6,20,
                new double[]{80,80,300,130},"","before"));
        NoteTextDraft edit=new NoteTextDraft("after","20",0xff2464d6,f.doc.pageToken(1),1,id,
                NoteTextDraft.structure(f.doc),false);
        f.view.insertPage(0);check(edit.resolvePage(f.doc)==2,"draft follows target page after front insertion");
        f.view.selectPage(edit.resolvePage(f.doc));check(f.view.selectText(edit.itemId),"draft reselects original text item");
        int count=f.doc.items().size();check(f.view.putText(edit.text,20,edit.color),"edited draft saves successfully");
        check(f.doc.items().size()==count&&f.doc.find(id)!=null&&f.doc.find(id).color==0xff2464d6
                &&f.doc.find(id).text.equals("after"),"edit preserves item id, count and arbitrary color");
        ByteArrayOutputStream wire=new ByteArrayOutputStream();NoteDocument.write(wire,f.doc);
        NoteDocument reopened=NoteDocument.read(new ByteArrayInputStream(wire.toByteArray()));
        check(reopened.pageIndex(edit.pageToken)<0,"disk reopen renews transient page tokens");
        NoteTextDraft rotation=new NoteTextDraft("paused text","24",0xff2464d6,f.doc.pageToken(2),2,id,
                NoteTextDraft.structure(f.doc),true);
        check(rotation.resolvePage(reopened)==2,"rotation restores draft using unchanged page structure");
        NoteCanvasView restored=new NoteCanvasView(f.context,reopened,"qa",new Host());restored.layout(0,0,800,600);
        restored.selectPage(rotation.resolvePage(reopened));check(restored.selectText(id),"rotation reselects existing object");
        count=reopened.items().size();check(restored.putText(rotation.text,24,rotation.color),"restored edit saves");
        check(reopened.items().size()==count&&reopened.find(id).text.equals("paused text"),"rotation edit never inserts duplicate");
        NoteTextDraft insertion=new NoteTextDraft("new","16",0xff223344,reopened.pageToken(2),2,0,
                NoteTextDraft.structure(reopened),false);
        restored.insertPage(0);check(insertion.resolvePage(reopened)==3,"new text draft follows page after insertion");
        restored.selectPage(3);restored.deletePage();check(insertion.resolvePage(reopened)==-1,"deleted draft page is rejected");
        restored.setReadOnly(true);int before=reopened.items().size();
        check(!restored.putText("blocked",20,0xff123456)&&reopened.items().size()==before,"read-only cannot save restored draft");
    }
    static void delayedImageTarget()throws Exception {
        Fixture f=new Fixture(3);f.view.selectPage(1);String token=f.doc.pageToken(1);
        f.view.selectPage(2);f.view.insertPage(0);f.view.movePage(2,3);
        int target=f.doc.pageIndex(token);check(target==3,"image target follows insertion and reordering");
        f.view.selectPage(target);check(f.view.insertImage("photo.png",640,480),"delayed image inserts on captured page");
        check(f.doc.pageItems(3).size()==1&&f.doc.pageItems(2).isEmpty(),"delayed image never lands on formerly selected page");
        ByteArrayOutputStream wire=new ByteArrayOutputStream();NoteDocument.write(wire,f.doc);
        NoteDocument reopened=NoteDocument.read(new ByteArrayInputStream(wire.toByteArray()));
        check(reopened.pageIndex(token)==-1,"rotation makes in-flight image token invalid for safe cancellation");
        f.view.deletePage();check(f.doc.pageIndex(token)==-1,"deleted image target is invalidated");
    }
    static void delayedTextDismiss(){
        NoteTextDialogLifecycle lifecycle=new NoteTextDialogLifecycle();
        final boolean[] draftPresent={true};
        NoteTextDialogLifecycle.Session beforePause=lifecycle.open();
        Runnable oldCallback=()->{if(lifecycle.dismissed(beforePause))draftPresent[0]=false;};
        lifecycle.pause(beforePause);
        NoteTextDialogLifecycle.Session resumed=lifecycle.open();
        check(lifecycle.isActive(resumed)&&draftPresent[0],"resume opens a new editor with draft intact");
        oldCallback.run();
        check(lifecycle.isActive(resumed)&&draftPresent[0],"late pre-pause dismiss cannot clear resumed editor");
        check(lifecycle.cancel(resumed),"explicit cancel owns current editor");
        draftPresent[0]=false;
        NoteTextDialogLifecycle.Session next=lifecycle.open();
        check(!lifecycle.dismissed(resumed)&&lifecycle.isActive(next)&&!draftPresent[0],"late cancel dismiss cannot affect a later editor");
        check(lifecycle.dismissed(next),"ordinary back-dismiss discards current draft");
        check(!lifecycle.isActive(next),"dismissed editor is no longer active");
    }
    static float wxScreen(Fixture f,double x)throws Exception{return (float)(400+(x-field(f.view,"cx"))*field(f.view,"zoom"));}
    static float wyScreen(Fixture f,double y)throws Exception{return (float)(300+(y-field(f.view,"cy"))*field(f.view,"zoom"));}
    static void worldStroke(Fixture f,double x,double y,double xx,double yy)throws Exception{stroke(f,wxScreen(f,x),wyScreen(f,y),wxScreen(f,xx),wyScreen(f,yy));}
    static void selectBox(Fixture f)throws Exception{f.view.setTool("lasso",0,2,true);f.view.setLassoFreeform(false);worldStroke(f,-160,-160,190,110);}
    static void editOperations()throws Exception {
        Fixture f=new Fixture("infinite",1);
        long ink=f.doc.newId(),text=f.doc.newId(),image=f.doc.newId(),shape=f.doc.newId();
        f.doc.add(new NoteDocument.Item(ink,NoteDocument.STROKE,0x55ff3344,4,new double[]{-100,-100,-30,-100},""));
        f.doc.add(new NoteDocument.Item(text,NoteDocument.TEXT,0xff334455,16,new double[]{-100,0,-20,40},"","draft text"));
        f.doc.add(new NoteDocument.Item(image,NoteDocument.IMAGE,0xffffffff,1,new double[]{20,0,80,50},"image.png"));
        f.doc.add(new NoteDocument.Item(shape,NoteDocument.RECTANGLE,0xff556677,3,new double[]{80,-100,140,-20},""));
        f.view.setLassoFilter(NoteCanvasView.LASSO_ALL);selectBox(f);check(f.view.selectionCount()==4,"rectangle lasso selects four object kinds");
        worldStroke(f,0,-25,30,15);close(f.doc.find(ink).left(),-70,.01,"group drag moves ink");close(f.doc.find(text).top(),40,.01,"group drag moves text");close(f.doc.find(image).left(),50,.01,"group drag moves image");
        f.view.undo();close(f.doc.find(ink).left(),-100,.001,"one undo restores group drag");close(f.doc.find(image).left(),20,.001,"same undo restores image");
        selectBox(f);worldStroke(f,140,50,180,80);check(f.doc.find(ink).width>4,"corner-handle gesture scales the selected group");check(f.doc.find(image).right()-f.doc.find(image).left()>60,"corner-handle gesture scales images too");f.view.undo();close(f.doc.find(ink).width,4,.001,"handle scale is one undo");
        selectBox(f);f.view.scaleSelection(1.25f);close(f.doc.find(ink).width,5,.001,"selection scale adjusts width");check(f.doc.find(image).right()-f.doc.find(image).left()>60,"selection scale adjusts image bounds");f.view.undo();
        selectBox(f);f.view.recolorSelection(0xff2464d6);check(f.doc.find(ink).color==0x552464d6,"recolor preserves marker alpha");check(f.doc.find(image).color==-1,"recolor preserves image");f.view.undo();
        selectBox(f);f.view.copySelection();check(f.view.hasClipboard(),"copy populates local clipboard");f.view.pasteSelection();check(f.doc.items().size()==8,"paste copies all selected kinds");
        Set<Long> ids=new HashSet<>();for(NoteDocument.Item item:f.doc.items())check(ids.add(item.id),"pasted ids are independent");
        f.view.undo();check(f.doc.items().size()==4,"one undo removes entire pasted group");
        selectBox(f);f.view.cutSelection();check(f.doc.items().isEmpty(),"cut removes group");f.view.undo();check(f.doc.items().size()==4,"undo cut restores group");
        f.view.setLassoFilter(NoteCanvasView.LASSO_HANDWRITING);selectBox(f);check(f.view.selectionCount()==1,"lasso handwriting filter excludes text/images/shapes");
        f.view.bringSelectionForward();check(f.doc.items().get(3).id==ink,"bring forward places at top");f.view.sendSelectionBackward();check(f.doc.items().get(0).id==ink,"send backward places at bottom");
        f.view.setLassoFilter(NoteCanvasView.LASSO_ALL);f.view.setLassoFreeform(true);time+=400;
        double[][] polygon={{-160,-160},{190,-160},{190,110},{-160,110},{-160,-160}};
        f.view.onTouchEvent(single(0,2,wxScreen(f,polygon[0][0]),wyScreen(f,polygon[0][1])));
        for(int i=1;i<polygon.length;i++)f.view.onTouchEvent(single(2,2,wxScreen(f,polygon[i][0]),wyScreen(f,polygon[i][1])));
        f.view.onTouchEvent(single(1,2,wxScreen(f,-160),wyScreen(f,-160)));check(f.view.selectionCount()==4,"freeform production gesture selects group");
        f.view.deleteSelection();check(f.doc.items().isEmpty(),"delete selection removes all kinds");f.view.undo();check(f.doc.items().size()==4,"delete selection is one undo");
    }
    static void eraserPagesAndInfinite()throws Exception {
        Fixture f=new Fixture("infinite",1);
        f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.STROKE,0xff333333,4,new double[]{-100,0,100,0},""));
        f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.STROKE,0xff333333,4,new double[]{-100,120,100,120},""));
        f.view.setTool("eraser",0,20,true);f.view.setWholeStrokeEraser(true);worldStroke(f,0,-5,0,5);
        check(f.doc.items().size()==1&&f.doc.items().get(0).top()==120,"whole stroke eraser removes only touched full path");f.view.undo();check(f.doc.items().size()==2,"whole stroke erase is one undo");
        f.view.setWholeStrokeEraser(false);worldStroke(f,0,-5,0,5);check(f.doc.items().size()==3,"partial eraser still retains both sides");f.view.undo();
        f.view.setTool("hand",0,2,true);int count=f.doc.items().size();worldStroke(f,-100,-100,100,100);check(f.doc.items().size()==count,"hand mode stylus input is read-only");
        f.view.setZoomPercent(1000);check(f.view.zoomPercent()==1000,"infinite supports ten times zoom");
        f.view.resetOrigin();close(field(f.view,"cx"),0,.001,"reset origin x");close(field(f.view,"cy"),0,.001,"reset origin y");check(f.view.zoomPercent()==1000,"origin retains scale");
        NoteDocument copy=f.view.snapshot();NoteCanvasView restored=new NoteCanvasView(f.context,copy,"qa",new Host());restored.layout(0,0,800,600);check(restored.zoomPercent()==1000,"ten times zoom survives reopen");f.view.fit();check(f.view.zoomPercent()==100,"fit returns default scale");
        Fixture paper=new Fixture(2);stroke(paper,300,150,400,200);long original=paper.doc.items().get(0).id;
        paper.view.duplicatePage();check(paper.doc.pageCount()==3&&paper.doc.currentPage()==1,"duplicate page inserts independent page");check(paper.doc.items().get(0).id!=original,"duplicated items use fresh ids");
        paper.view.clearPage();check(paper.doc.items().isEmpty()&&paper.doc.pageItems(0).size()==1,"clear affects selected page only");paper.view.undo();check(paper.doc.items().size()==1,"clear page undo restores content");
        paper.view.deletePage();check(paper.doc.pageCount()==2&&paper.doc.pageItems(0).size()==1,"delete copied page preserves original");
        paper.view.setZoomPercent(1000);check(paper.view.zoomPercent()==300,"finite page retains 300 percent ceiling");
    }
    static void presetsAndDisabledMenus(){
        ArrayList<NotePenPresets.Preset> presets=NotePenPresets.read(null);check(presets.size()==3,"pen box defaults");
        String wire=NotePenPresets.write(presets);ArrayList<NotePenPresets.Preset> read=NotePenPresets.read(wire);
        check(read.size()==3&&read.get(2).width==18,"pen presets roundtrip");check(NotePenPresets.read("").isEmpty(),"intentionally emptied pen box stays empty");
        check(NotePenPresets.read("pen,ff000000,NaN;pen,ff000000,100;lasso,ff000000,2").isEmpty(),"invalid presets rejected");
        for(int i=0;i<20;i++)presets.add(new NotePenPresets.Preset("pen",0xff001000+i,2));check(NotePenPresets.read(NotePenPresets.write(presets)).size()==8,"pen box remains bounded");
        Context context=new Context();View anchor=new View(context);final int[] calls={0};
        Dialog menu=NoteActionMenu.showActions(context,anchor,"selection",new NoteActionMenu.Action("×","Disabled","No selection",null),new NoteActionMenu.Action("+","Enabled","Action",()->calls[0]++));
        List<View> rows=new ArrayList<>();walk(menu.content,rows);rows.get(0).performClick();check(calls[0]==0&&menu.shown,"unavailable action neither runs nor dismisses");
        rows.get(1).performClick();check(calls[0]==1&&!menu.shown,"enabled action works alongside disabled row");
    }

    static void longEdgePull(Fixture f){
        time+=400;f.view.onTouchEvent(single(0,1,700,300));f.view.onTouchEvent(single(2,1,650,300));time+=500;
        f.view.onTouchEvent(single(2,1,100,300));f.view.onTouchEvent(single(1,1,100,300));
    }
    static void shapeInteriorSelection()throws Exception{
        Fixture f=new Fixture("infinite",1);f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.CIRCLE,0xff333333,2,new double[]{-100,-100,100,100},""));
        f.view.setTool("lasso",0,2,true);f.view.setLassoFreeform(false);worldStroke(f,-10,-10,10,10);check(f.view.selectionCount()==0,"rectangle lasso does not select empty circle interior");
        f.doc.clearPage();f.doc.add(new NoteDocument.Item(f.doc.newId(),NoteDocument.STROKE,0xff333333,2,new double[]{-100,-100,100,100},""));
        worldStroke(f,-90,60,-60,90);check(f.view.selectionCount()==0,"lasso does not select empty diagonal bounding-box corner");
        worldStroke(f,-10,-10,10,10);check(f.view.selectionCount()==1,"lasso selects actual diagonal path crossing");
    }
    static void readOnlyAndLegacy()throws Exception{
        Fixture f=new Fixture(1);stroke(f,200,150,300,200);int objects=f.doc.items().size();
        f.view.setHorizontalPages(true);f.view.fit();f.view.setTool("hand",0,2,true);f.view.setReadOnly(true);
        longEdgePull(f);check(f.doc.pageCount()==1,"read-only edge pull cannot append a page");
        f.view.setTool("pen",0,2,false);stroke(f,200,150,300,200);check(f.doc.items().size()==objects,"read-only blocks stylus even if tool is pen");
        time+=400;f.view.onTouchEvent(single(0,4,200,150));f.view.onTouchEvent(single(1,4,300,200));check(f.doc.items().size()==objects,"read-only blocks hardware eraser");
        f.view.addPage();f.view.insertPage(0);f.view.duplicatePage();f.view.deletePage();f.view.clearPage();f.view.putText("blocked",16,0xff000000);f.view.insertImage("blocked.png",20,20);f.view.undo();f.view.redo();
        check(f.doc.pageCount()==1&&f.doc.items().size()==objects,"read-only rejects public mutation APIs");
        f.view.setReadOnly(false);f.view.setTool("hand",0,2,true);f.view.fit();longEdgePull(f);check(f.doc.pageCount()==2,"control gesture really appends in editable hand mode");
        Fixture legacy=new Fixture("infinite",3);
        check(NoteEditorPresentation.hasPageNavigation(legacy.doc),"legacy multi-canvas keeps navigation");
        check(NoteEditorPresentation.pdfScope(legacy.doc).contains("3 张独立画布"),"legacy PDF scope includes all canvases");
        for(int i=0;i<3;i++){legacy.view.selectPage(i);legacy.view.putText("canvas "+i,16,0xff123456);}
        for(int i=0;i<3;i++){legacy.view.selectPage(i);check(legacy.doc.items().get(0).text.equals("canvas "+i),"legacy canvas remains reachable");check(NoteEditorPresentation.pagePosition(legacy.doc).equals("画布 "+(i+1)+"/3"),"legacy position label follows selection");}
        Fixture single=new Fixture("infinite",1);check(!NoteEditorPresentation.hasPageNavigation(single.doc),"new single infinite has location UI");check(NoteEditorPresentation.pagePosition(single.doc).equals("无限草稿"),"single infinite label");
    }

    static void insertionChoiceThroughCanvas(){
        Fixture f=new Fixture(3);
        for(int page=0;page<3;page++){f.view.selectPage(page);f.view.putText("original " + page,16,0xff123456);}
        int before=PageInsertionPosition.index(1,f.doc.pageCount(),true);
        f.view.insertPage(before);
        check(f.doc.pageCount()==4&&f.doc.currentPage()==1,"before choice selects inserted page");
        check(f.doc.pageItems(1).isEmpty(),"before choice creates blank page");
        check("original 1".equals(f.doc.pageItems(2).get(0).text),"before choice preserves target content after blank");
        int after=PageInsertionPosition.index(2,f.doc.pageCount(),false);
        f.view.insertPage(after);
        check(f.doc.pageCount()==5&&f.doc.currentPage()==3,"after choice selects inserted page");
        check(f.doc.pageItems(3).isEmpty(),"after choice creates blank page");
        check("original 1".equals(f.doc.pageItems(2).get(0).text)&&"original 2".equals(f.doc.pageItems(4).get(0).text),"after choice preserves neighbours");
    }

    public static void main(String[] args)throws Exception {
        try {shapeInteriorSelection();readOnlyAndLegacy();insertionChoiceThroughCanvas();editOperations();eraserPagesAndInfinite();presetsAndDisabledMenus();pinchAndRestore();boundaryEditing();selectionAndAppend();drawingIsolation();palmAndModes();menu();textDraftAndPageTarget();delayedImageTarget();delayedTextDismiss();System.out.println("PASS NoteViewQaTest "+assertions+" assertions (production Views, Android recording shims; not device rendering)");System.exit(0);}
        catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}

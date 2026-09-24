package cn.qingyue.pdf;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.Toast;
import android.widget.*;
import android.graphics.Color;
import android.text.InputType;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import java.io.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One integration surface for navigation, search, selection, export and source links. */
public final class ReaderActions implements AutoCloseable {
    public interface ExportListener {void progress(int completed,int total);void complete(PdfAnnotationExporter.Report report);void failed(String message);}
    public interface SourceConsumer {void accept(ReaderSourceLink source);}
    private final Activity owner;private final PdfReaderView view;private final File pdf,directory;private final String documentId,documentName;private final int pageCount;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"qingyue-reader-actions");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private final Handler main=new Handler(Looper.getMainLooper());private final PdfSearchDialog.Session searchSession;
    private ReaderDocument document=new ReaderDocument();private long documentRevision;private PdfSearchDialog searchDialog;private ReaderNavigationDialog navigationDialog;private Dialog actionDialog;private boolean closed,metadataReady,metadataWritable;
    private ActionMode selectionActionMode;
    private AtomicBoolean activeExport;private SourceConsumer sourceConsumer;private ReaderDialogs.AdvancedToolSelection toolSelection;
    private final ArrayList<Runnable> metadataCallbacks=new ArrayList<>();
    private final ArrayList<java.util.List<ReaderDocument.TextBox>> textUndo=new ArrayList<>();
    private String pendingText;private int pendingColor;private float pendingSize;
    private int metadataLoadToken;private boolean navigationRequested,navigationPagesRequested;
    private ReaderNavigationDialog.Selection pageInsertion;

    public ReaderActions(Activity owner,PdfReaderView view,File pdf,File directory,String documentId,String documentName,int pageCount){
        if(owner==null||view==null||pdf==null||directory==null)throw new IllegalArgumentException("Reader actions input");this.owner=owner;this.view=view;this.pdf=pdf;this.directory=directory;this.documentId=documentId;this.documentName=documentName==null?"":documentName;this.pageCount=pageCount;this.searchSession=PdfSearchDialog.retainedSession(pdf);view.setTextBoxListener(new PdfReaderView.TextBoxListener(){public void selected(ReaderDocument.TextBox box){editText(box,null);}public void moved(ReaderDocument.TextBox box,float x,float y){if(!requireWritableMetadata())return;try{recordTextUndo();document.updateTextBox(box,box.text,box.color,x,y,box.size);textChanged();}catch(RuntimeException problem){toast("文字框移动失败，请重试");}}public void placed(int page,float x,float y){if(pendingText!=null&&requireWritableMetadata()){try{ReaderDocument.TextBox preview=new ReaderDocument.TextBox("preview",page,pendingText,pendingColor,0,0,pendingSize,0);float maxX=Math.max(0,1-PdfTextBoxes.estimatedWidth(preview)),maxY=Math.max(0,view.pageHeight(page)-PdfTextBoxes.lines(pendingText).size()*PdfTextBoxes.lineHeight(preview));recordTextUndo();document.addTextBox(page,pendingText,pendingColor,Math.min(x,maxX),Math.min(y,maxY),pendingSize);if(PdfTextBoxes.estimatedWidth(preview)>1)toast("文字行过长，可点击文字框编辑并换行");pendingText=null;textChanged();}catch(RuntimeException problem){toast("文字框数量已达上限或内容无效");}}}});loadMetadata();
    }
    public void setSourceConsumer(SourceConsumer consumer){sourceConsumer=consumer;}
    public void setPageInsertion(ReaderNavigationDialog.Selection callback){pageInsertion=callback;}
    public synchronized boolean exporting(){return activeExport!=null;}
    public void setAdvancedToolSelection(ReaderDialogs.AdvancedToolSelection selection){toolSelection=selection;}
    public ReaderDocument metadata(){return document.copy();}
    public boolean metadataReady(){return metadataReady;}
    public boolean metadataWritable(){return metadataReady&&metadataWritable;}
    public void whenMetadataReady(Runnable action){if(closed||action==null)return;if(metadataReady)action.run();else metadataCallbacks.add(action);}
    private void deliverMetadataCallbacks(){ArrayList<Runnable> ready=new ArrayList<>(metadataCallbacks);metadataCallbacks.clear();for(Runnable action:ready)action.run();}
    private void loadMetadata(){final int token=++metadataLoadToken;io.execute(()->{try{ReaderStore.Loaded loaded=ReaderStore.loadVersioned(directory,documentId);main.post(()->{if(token==metadataLoadToken)publishMetadata(loaded);});try{ReaderStore.flushPending(directory,documentId);}catch(IOException ignored){} }catch(IOException|RuntimeException problem){main.post(()->{if(!closed&&token==metadataLoadToken){metadataReady=true;metadataWritable=false;toast("书签与文字高亮读取失败；为保护原数据，本次仅可阅读和复制");deliverMetadataCallbacks();}});}});}
    private void publishMetadata(ReaderStore.Loaded loaded){
        if(closed)return;
        boolean published=ReaderStore.runIfCurrent(directory,documentId,loaded.revision,()->{if(documentRevision!=loaded.revision)textUndo.clear();document=loaded.document;documentRevision=loaded.revision;metadataReady=true;metadataWritable=true;view.setReaderDocument(document);});
        if(published)deliverMetadataCallbacks();
        if(!published)loadMetadata();
    }
    public void showSearch(){if(closed)return;dismissSearch();searchDialog=PdfSearchDialog.showDetailed(owner,pdf,searchSession,(result,hit)->{if(!closed)view.setSearchResults(searchSession.results(),result,hit);});}
    public boolean nextSearchHit(){return view.nextSearchHit();}
    public boolean previousSearchHit(){return view.previousSearchHit();}
    public void clearSearch(){view.clearSearchHighlights();}
    public void showNavigator(){
        requestNavigator(false);
    }
    public void showPageNavigator(){requestNavigator(true);}
    private void requestNavigator(boolean pages){
        if(closed||navigationRequested||owner.isFinishing()||owner.isDestroyed())return;
        dismissNavigation();navigationRequested=true;navigationPagesRequested=pages;metadataReady=false;metadataWritable=false;
        whenMetadataReady(()->{if(!navigationRequested||closed)return;navigationRequested=false;openNavigator();});loadMetadata();
    }
    private void openNavigator(){
        if(closed||owner.isFinishing()||owner.isDestroyed()||!requireWritableMetadata())return;
        try{navigationDialog=ReaderNavigationDialog.show(owner,pdf,directory,documentId,pageCount,view.page(),document,page->{if(!closed)view.jumpTo(page);},updated->{if(closed)return;document=updated;view.setReaderDocument(document);saveMetadata();});navigationDialog.setPageInsertion(pageInsertion);if(navigationPagesRequested)navigationDialog.showPages();}
        catch(RuntimeException|LinkageError problem){CrashDiagnostics.markStage("导航创建");CrashDiagnostics.record(owner,"导航打开失败",problem);toast("导航未能打开，可在首页工具中查看故障记录");}
    }
    public void toggleBookmark(){if(!requireWritableMetadata()||actionDialog!=null)return;final int page=view.page();actionDialog=ReaderNavigationDialog.editBookmarkName(owner,page,"第 "+(page+1)+" 页",false,label->{if(!requireWritableMetadata())return;try{document.addBookmark(page,label);saveMetadata();toast("已添加书签");}catch(IllegalStateException limit){toast("书签数量已达上限");}});actionDialog.setOnDismissListener(v->actionDialog=null);}
    public void showAddText(){showAddText("");}
    /** Opens a multiline text editor, then lets the reader place its box on the PDF page. */
    public void showAddText(String initialText){if(!requireWritableMetadata())return;editText(null,initialText);}
    public boolean undoTextEdit(){if(!requireWritableMetadata()||textUndo.isEmpty())return false;document.replaceTextBoxes(textUndo.remove(textUndo.size()-1));textChanged();return true;}
    private void recordTextUndo(){if(textUndo.size()>=30)textUndo.remove(0);textUndo.add(document.textBoxes());}
    private void textChanged(){view.setReaderDocument(document);saveMetadata();}
    private void editText(ReaderDocument.TextBox existing,String initial){
        if(!requireWritableMetadata()||owner.isFinishing()||owner.isDestroyed())return;
        android.content.SharedPreferences prefs=owner.getSharedPreferences("reader_text_style",Activity.MODE_PRIVATE);
        LinearLayout form=new LinearLayout(owner);form.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*owner.getResources().getDisplayMetrics().density);form.setPadding(pad,pad,pad,pad);
        EditText content=new EditText(owner);content.setHint("输入或粘贴多行文字");content.setMinLines(4);content.setMaxLines(12);content.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);content.setText(existing==null?initial:existing.text);form.addView(content);
        EditText size=new EditText(owner);size.setHint("字号");size.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);size.setText(String.valueOf(existing==null?prefs.getInt("size",16):Math.round(existing.size*600)));form.addView(size);
        EditText colorInput=new EditText(owner);colorInput.setHint("颜色 #RRGGBB");colorInput.setSingleLine(true);colorInput.setText(String.format(java.util.Locale.ROOT,"#%06X",(existing==null?prefs.getInt("color",Color.BLACK):existing.color)&0xffffff));form.addView(colorInput);
        AlertDialog.Builder builder=new AlertDialog.Builder(owner).setTitle(existing==null?"添加文字":"编辑文字框").setView(form).setNegativeButton("取消",(d,w)->{});
        if(existing!=null)builder.setNeutralButton("删除",(d,w)->{recordTextUndo();document.remove(existing.id);textChanged();});
        AlertDialog dialog=builder.setPositiveButton(existing==null?"放置到页面":"保存",null).create();actionDialog=dialog;dialog.setOnDismissListener(ignored->{if(actionDialog==dialog)actionDialog=null;});dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            String value=content.getText().toString();if(value.trim().isEmpty()||value.length()>8192)throw new IllegalArgumentException("文字须为 1–8192 个字符");int points=Integer.parseInt(size.getText().toString().trim());if(points<6||points>96)throw new IllegalArgumentException("字号须为 6–96 pt");int chosen=Color.parseColor(colorInput.getText().toString().trim());float normalized=points/600f;
            prefs.edit().putInt("size",points).putInt("color",chosen).apply();if(existing==null){pendingText=value;pendingColor=chosen;pendingSize=normalized;view.beginTextPlacement();toast("点击 PDF 页面放置文字");}
            else{recordTextUndo();document.updateTextBox(existing,value,chosen,existing.x,existing.y,normalized);textChanged();}dialog.dismiss();
        }catch(RuntimeException invalid){toast(invalid.getMessage()==null?"请输入有效的文字、字号和颜色":invalid.getMessage());}}));dialog.show();
    }
    public void beginTextSelection(){
        if(closed)return;final int page=view.page();toast("正在读取当前页文字…");io.execute(()->{try(PdfTextSearch search=new PdfTextSearch(owner.getApplicationContext())){PdfTextSearch.PageText text=search.extractPage(pdf,page);main.post(()->{if(closed||view.page()!=page)return;if(!text.hasText()){toast("当前页没有可选择文字；扫描件暂不支持文字选择");return;}view.beginTextSelection(text,this::showTextSelectionActions);toast("拖动文字即可选择");});}
            catch(IOException|RuntimeException problem){main.post(()->{if(!closed)toast("无法读取当前页文字；扫描件或受限制 PDF 不能选择");});}});
    }
    private void showTextSelectionActions(PdfReaderView.TextSelection selection){
        if(closed||selection==null||owner.isFinishing()||owner.isDestroyed())return;
        if(selectionActionMode!=null){selectionActionMode.invalidate();return;}
        // A contextual toolbar leaves the page touchable, so the selection handles can move.
        selectionActionMode=owner.startActionMode(new ActionMode.Callback(){
            @Override public boolean onCreateActionMode(ActionMode mode,Menu menu){
                mode.setTitle("文字选择");
                menu.add(0,1,0,"复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0,2,1,"高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0,3,2,"摘录到笔记").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode,Menu menu){
                menu.findItem(2).setEnabled(metadataWritable());return true;
            }
            @Override public boolean onActionItemClicked(ActionMode mode,MenuItem item){
                PdfReaderView.TextSelection selected=view.textSelection();
                if(closed||selected==null){mode.finish();return true;}
                if(item.getItemId()==1){if(view.copySelectedText())toast("文字已复制");}
                else if(item.getItemId()==2){
                    if(!requireWritableMetadata())return true;
                    try{view.highlightSelectedText(document,0xfff6c343);saveMetadata();toast("已保存文字高亮");}
                    catch(RuntimeException problem){toast("文字高亮数量已达上限");return true;}
                }else if(item.getItemId()==3){
                    ReaderSourceLink source=ReaderSourceLink.selection(documentId,documentName,selected.page,selected.text,selected.boxes);
                    if(sourceConsumer!=null)sourceConsumer.accept(source);
                    else{ClipboardManager clipboard=(ClipboardManager)owner.getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("晴阅来源",source.toUri()));toast("来源链接已复制");}
                }else return false;
                mode.finish();return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode){
                if(selectionActionMode==mode){selectionActionMode=null;view.cancelTextSelection();}
            }
        });
    }
    public void cancelTextSelection(){
        ActionMode active=selectionActionMode;selectionActionMode=null;
        if(active!=null)active.finish();view.cancelTextSelection();
    }
    public void showAdvancedTools(){if(closed)return;dismissAction();actionDialog=ReaderDialogs.advancedTools(owner,view.hasInkSelection(),tool->{if(toolSelection!=null)toolSelection.selected(tool);else view.setAdvancedTool(tool);},()->{if(view.copyInkSelection())toast("已复制所选笔迹");},()->{if(view.deleteInkSelection())toast("已删除所选笔迹，可撤销恢复");});}
    public ReaderSourceLink currentSource(){PdfReaderView.TextSelection selection=view.textSelection();return selection==null?ReaderSourceLink.page(documentId,documentName,view.page()):ReaderSourceLink.selection(documentId,documentName,selection.page,selection.text,selection.boxes);}
    public boolean canRestoreLastClear(){return view.canRestoreLastClear(view.page());}
    public void restoreLastClear(){view.restoreLastClear(view.page());}

    public void exportPdf(Uri destination,PdfAnnotationExporter.PageRange range,ExportListener listener){exportPdf(destination,range,false,listener);}
    /** Set allowSignatureInvalidation only after an explicit warning/confirmation in the host UI. */
    public void exportPdf(Uri destination,PdfAnnotationExporter.PageRange range,boolean allowSignatureInvalidation,ExportListener listener){
        if(closed||destination==null||range==null)return;if(!metadataReady){fail(listener,"书签与高亮仍在读取，请稍后再导出");return;}if(!metadataWritable){fail(listener,"书签与高亮数据无法读取；为避免遗漏，本次未导出");return;}AtomicBoolean token=beginExport(listener);if(token==null)return;ReaderDocument snapshot=document.copy();
        io.execute(()->{PdfAnnotationExporter.Report report=null;String failure=null;try{if(token.get())throw new PdfAnnotationExporter.ExportCancelled();if(PdfAnnotationExporter.sourceHasSignatures(owner.getApplicationContext(),pdf)&&!allowSignatureInvalidation)throw new SignatureWarning();if(token.get())throw new PdfAnnotationExporter.ExportCancelled();try(OutputStream output=owner.getContentResolver().openOutputStream(destination,"w")){if(output==null)throw new IOException("Cannot open destination");report=PdfAnnotationExporter.exportPdf(owner.getApplicationContext(),pdf,directory,documentId,snapshot,range,output,token::get,(done,total)->main.post(()->{if(!closed&&listener!=null)listener.progress(done,total);}));}}
            catch(SignatureWarning warning){failure="原 PDF 含数字签名；导出副本会使签名失效，请确认后重试";}
            catch(PdfAnnotationExporter.ExportCancelled cancelled){failure="导出已取消；目标位置可能保留未完成文件";}
            catch(OutOfMemoryError problem){failure="内存不足，导出未完成；请减少导出页数后重试";}
            catch(IOException|RuntimeException problem){failure="导出失败，请确认 PDF 完整且允许修改";}
            final PdfAnnotationExporter.Report ready=report;final String message=failure;main.post(()->{try{if(!closed&&listener!=null){if(message==null)listener.complete(ready);else listener.failed(message);}}finally{finishExport(token);}});});
    }
    public void exportCurrentPagePng(Uri destination,int width,ExportListener listener){exportPagePng(destination,view.page(),width,listener);}
    public void exportPagePng(Uri destination,int page,int width,ExportListener listener){
        if(closed||destination==null)return;if(page<0||page>=pageCount){fail(listener,"导出页码不存在");return;}if(!metadataReady){fail(listener,"书签与高亮仍在读取，请稍后再导出");return;}if(!metadataWritable){fail(listener,"书签与高亮数据无法读取；为避免遗漏，本次未导出");return;}AtomicBoolean token=beginExport(listener);if(token==null)return;ReaderDocument snapshot=document.copy();
        io.execute(()->{PdfAnnotationExporter.Report report=null;String failure=null;try{if(token.get())throw new PdfAnnotationExporter.ExportCancelled();try(OutputStream output=owner.getContentResolver().openOutputStream(destination,"w")){if(output==null)throw new IOException("Cannot open destination");report=PdfAnnotationExporter.exportPagePng(pdf,directory,documentId,snapshot,page,width,output,token::get);}}
            catch(PdfAnnotationExporter.ExportCancelled cancelled){failure="导出已取消；目标位置可能保留未完成文件";}
            catch(IOException|RuntimeException problem){failure="当前页图片导出失败";}
            final PdfAnnotationExporter.Report ready=report;final String message=failure;main.post(()->{try{if(!closed&&listener!=null){if(message==null)listener.complete(ready);else listener.failed(message);}}finally{finishExport(token);}});});
    }
    public synchronized void cancelExport(){if(activeExport!=null)activeExport.set(true);}
    public static Intent createPdfDestination(String fileName){return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE,safeName(fileName,".pdf"));}
    public static Intent createPngDestination(String fileName){return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE,safeName(fileName,".png"));}
    public static Intent share(Uri uri,String mimeType){return new Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
    private static String safeName(String name,String suffix){String value=(name==null?"":name).replaceAll("[\\\\/:*?\"<>|]","_").trim();if(value.length()==0)value="晴阅导出";if(value.length()>120)value=value.substring(0,120);if(!value.toLowerCase(java.util.Locale.ROOT).endsWith(suffix))value+=suffix;return value;}
    private void saveMetadata(){
        if(!metadataWritable)return;ReaderDocument snapshot=document.copy();long revision=ReaderStore.stageIfCurrent(directory,documentId,snapshot,documentRevision);
        if(revision<0){dismissNavigation();metadataReady=false;metadataWritable=false;toast("书签与高亮已在另一窗口更新，正在重新读取");loadMetadata();return;}
        documentRevision=revision;io.execute(()->{try{ReaderStore.saveStaged(directory,documentId,revision);}catch(IOException|RuntimeException problem){main.post(()->{if(!closed)toast("书签或文字高亮尚未保存，请检查空间后重试");});}});
    }
    private boolean requireWritableMetadata(){if(closed)return false;if(!metadataReady){toast("正在读取书签与高亮，请稍后再试");return false;}if(!metadataWritable){toast("原书签数据无法读取，本次不会覆盖它");return false;}return true;}
    private synchronized AtomicBoolean beginExport(ExportListener listener){if(activeExport!=null){fail(listener,"已有导出任务正在进行");return null;}activeExport=new AtomicBoolean();return activeExport;}
    private synchronized void finishExport(AtomicBoolean token){if(activeExport==token)activeExport=null;}
    private void fail(ExportListener listener,String message){if(listener!=null)main.post(()->{if(!closed)listener.failed(message);});}
    private static final class SignatureWarning extends IOException {}
    public boolean handleBack(){if(view.isPlacingText()){view.cancelTextPlacement();pendingText=null;return true;}if(view.isSelectingText()||selectionActionMode!=null){cancelTextSelection();return true;}if(actionDialog!=null&&actionDialog.isShowing()){dismissAction();return true;}if(searchDialog!=null&&searchDialog.isShowing()){dismissSearch();return true;}if(navigationDialog!=null&&navigationDialog.isShowing()){dismissNavigation();return true;}return false;}
    public void pauseDialogs(){view.cancelTextPlacement();pendingText=null;cancelTextSelection();dismissSearch();dismissNavigation();dismissAction();}
    private void dismissSearch(){if(searchDialog!=null){searchDialog.dismiss();searchDialog=null;}}
    private void dismissNavigation(){navigationRequested=false;if(navigationDialog!=null){navigationDialog.dismiss();navigationDialog=null;}}
    private void dismissAction(){if(actionDialog!=null){actionDialog.dismiss();actionDialog=null;}}
    private void toast(String value){if(!owner.isFinishing()&&!owner.isDestroyed())Toast.makeText(owner,value,Toast.LENGTH_LONG).show();}
    /** Called only by the host's worker, after close(), before releasing its document lease. */
    void awaitClosed(){
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Do not wait for PDF work on the UI thread");
        boolean interrupted=false;
        while(!io.isTerminated())try{io.awaitTermination(1,java.util.concurrent.TimeUnit.DAYS);}catch(InterruptedException ignored){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    @Override public void close(){if(closed)return;cancelExport();closed=true;metadataCallbacks.clear();pauseDialogs();view.cancelTextSelection();view.setTextBoxListener(null);main.removeCallbacksAndMessages(null);io.shutdown();}
}

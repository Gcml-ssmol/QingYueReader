package cn.qingyue.pdf;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.Locale;

/** User OCR corrections; stored separately from the immutable imported PDF. */
public final class OcrSidecar {
    private OcrSidecar() { }
    public static File directory(Context context,String docId) throws IOException {
        if (docId==null || !docId.matches("[0-9a-fA-F-]{36}")) throw new IOException("文档编号无效");
        return new File(new File(context.getFilesDir(),"recognition"),docId);
    }
    public static void save(Context context,String docId,int page,String sourceHash,String text) throws IOException {
        write(context,docId,page,sourceHash,null,text);
    }
    /** V2 binds the user's imported-document identity to the bytes of the PDF being edited. */
    public static void saveVerified(Context context,String docId,int page,String identityHash,
            String actualPdfHash,String text)throws IOException {
        write(context,docId,page,identityHash,actualPdfHash,text);
    }
    private static void write(Context context,String docId,int page,String identityHash,
            String actualPdfHash,String text)throws IOException {
        synchronized(TrashManager.class) {
        if (page<0 || page>1_000_000 || !validHash(identityHash)
                || (actualPdfHash!=null&&!validHash(actualPdfHash)))
            throw new IOException("OCR 来源无效");
        if (text==null || text.length()>2_000_000) throw new IOException("OCR 文字过长");
        if (TrashManager.isDeletionStaged(docId)) throw new IOException("文档正在删除，无法保存 OCR");
        LibraryStore.State library=LibraryStore.load(context);
        if(!library.allowSave||!library.damaged.isEmpty())throw new IOException("资料库当前不可写，无法保存 OCR");
        boolean live=false;
        for(LibraryItem item:library.items)if(docId.equals(item.id)&&!item.isNote()&&!item.isTrashed()){live=true;break;}
        if(!live)throw new IOException("文档已移除，无法保存 OCR");
        // OCR can outlive its reader. Reject a result computed before an in-place PDF edit.
        if(actualPdfHash!=null&&!actualPdfHash.equalsIgnoreCase(pdfHash(new File(context.getFilesDir(),docId+".pdf"))))
            throw new IOException("PDF 已更新，请重新识别此页后保存 OCR");
        File dir=directory(context,docId);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法保存 OCR 结果");
        AtomicFile file=new AtomicFile(new File(dir,"page-"+page+".txt"));
        FileOutputStream out=null;
        try {
            out=file.startWrite();
            String header=actualPdfHash==null?"QY-OCR-1\n"+identityHash.toLowerCase(Locale.ROOT)+"\n"
                    :"QY-OCR-2\n"+identityHash.toLowerCase(Locale.ROOT)+"\n"+
                    actualPdfHash.toLowerCase(Locale.ROOT)+"\n";
            byte[] payload=(header+text).getBytes(StandardCharsets.UTF_8);
            if(payload.length>8_000_000)throw new IOException("OCR 文字超过存储上限");
            out.write(payload);
            file.finishWrite(out);
        } catch(IOException failure) { if (out!=null) file.failWrite(out); throw failure; }
        }
    }
    public static String load(Context context,String docId,int page,String sourceHash) throws IOException {
        if (page<0 || page>1_000_000 || !validHash(sourceHash))
            throw new IOException("OCR 来源无效");
        String value=read(context,docId,page),prefix="QY-OCR-1\n"+sourceHash.toLowerCase(Locale.ROOT)+"\n";
        return value.startsWith(prefix)?value.substring(prefix.length()):"";
    }
    /** Legacy converted sidecars can be opened for correction, but not treated as verified search data. */
    public static String loadForPdf(Context context,String docId,int page,String identityHash,
            String actualPdfHash,boolean allowLegacyConverted)throws IOException {
        if(page<0||page>1_000_000||!validHash(identityHash)||!validHash(actualPdfHash))throw new IOException("OCR 来源无效");
        String value=read(context,docId,page),identity=identityHash.toLowerCase(Locale.ROOT),actual=actualPdfHash.toLowerCase(Locale.ROOT);
        String verified="QY-OCR-2\n"+identity+"\n"+actual+"\n";
        if(value.startsWith(verified))return value.substring(verified.length());
        String legacyActual="QY-OCR-1\n"+actual+"\n";
        if(value.startsWith(legacyActual))return value.substring(legacyActual.length());
        String legacyIdentity="QY-OCR-1\n"+identity+"\n";
        return allowLegacyConverted&&value.startsWith(legacyIdentity)?value.substring(legacyIdentity.length()):"";
    }
    private static boolean validHash(String hash){return hash!=null&&hash.matches("[0-9a-fA-F]{64}");}
    private static String pdfHash(File file)throws IOException {
        try{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=new java.io.FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
            StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static String read(Context context,String docId,int page)throws IOException {
        File target=new File(directory(context,docId),"page-"+page+".txt");
        if (!target.isFile() && !new File(target.getPath()+".bak").isFile()) return "";
        AtomicFile atomic=new AtomicFile(target);
        try(InputStream in=atomic.openRead()) {
            byte[] bytes=new byte[8_000_001]; int count=0,n;
            while((n=in.read(bytes,count,bytes.length-count))!=-1) {
                count+=n; if(count==bytes.length) throw new IOException("OCR 侧车过大");
            }
            try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,count)).toString();}
            catch(CharacterCodingException malformed){throw new IOException("OCR 侧车编码损坏",malformed);}
        }
    }
    /** Library deletion/backup integration: treat this entire directory as user data. */
    public static void delete(Context context,String docId) throws IOException {
        File dir=directory(context,docId); File[] files=dir.listFiles();
        if (files!=null) for (File file:files) {
            if (!file.getName().matches("page-[0-9]+\\.txt(\\.bak)?") || !file.delete())
                throw new IOException("OCR 侧车删除失败");
        }
        if (dir.isDirectory() && !dir.delete()) throw new IOException("OCR 目录删除失败");
    }
}

package cn.qingyue.pdf;

import android.content.Context;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Recoverable library deletion. Moving to trash is metadata-only; permanent deletion is staged. */
public final class TrashManager {
    private static final int MANIFEST_MAGIC=0x51594454;
    private static final int MANIFEST_VERSION=1;
    private static final int MAX_MANIFEST_MOVES=100_000;
    private static final String MANIFEST_NAME="transaction.bin";
    private static final Object ACTIVE_LOCK=new Object();
    private static final Set<String> ACTIVE_TRANSACTIONS=new HashSet<>();
    private static final Set<String> STAGED_IDS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private TrashManager() { }

    public static void moveToTrash(LibraryItem item,long now) {
        if(item==null)return;item.trashedAt=Math.max(1,now);item.modified=Math.max(item.modified,item.trashedAt);
    }

    public static void restore(LibraryItem item,long now) {
        if(item==null)return;item.trashedAt=0;item.modified=Math.max(item.modified,Math.max(1,now));
    }

    /**
     * Moves every owned resource to a same-filesystem transaction directory. The caller must
     * atomically save an index without the item, then commit(). If index saving fails, rollback().
     */
    public static Deletion stagePermanentDelete(Context context,LibraryItem item)throws IOException {
        synchronized(TrashManager.class){synchronized(NoteStore.class){synchronized(ReaderStore.class){synchronized(InkStore.class){
            Deletion deletion=stagePermanentDeleteLocked(context,item);
            STAGED_IDS.add(item.id);
            return deletion;
        }}}}
    }
    static boolean isDeletionStaged(String id){return STAGED_IDS.contains(id);}

    private static Deletion stagePermanentDeleteLocked(Context context,LibraryItem item)throws IOException {
        if(context==null||item==null)throw new IOException("删除参数无效");item.validate();
        if(item.isNote())NoteStore.flushPending(context,item.id);
        else{
            ReaderStore.flushPending(context.getFilesDir(),item.id);
            InkStore.flushDocument(context.getFilesDir(),item.id);
        }
        File files=context.getFilesDir();File transactionRoot=beginTransaction(context);
        ArrayList<Move> moves=new ArrayList<>();
        try{
            if(item.isNote())addIfExists(moves,new File(new File(files,"notes"),item.id),new File(transactionRoot,"note"));
            else addIfExists(moves,new File(files,item.id+".pdf"),new File(transactionRoot,"content.pdf"));
            addIfExists(moves,new File(new File(files,"recognition"),item.id),new File(transactionRoot,"recognition"));
            addIfExists(moves,new File(files,item.id+".reader"),new File(transactionRoot,"reader/metadata.reader"));
            addIfExists(moves,new File(files,item.id+".reader.bak"),new File(transactionRoot,"reader/metadata.reader.bak"));
            addIfExists(moves,new File(files,item.id+".reader.new"),new File(transactionRoot,"reader/metadata.reader.new"));
            File[] roots=files.listFiles();if(roots==null)throw new IOException("无法读取应用文件目录");
            for(File file:roots){String name=file.getName();if(file.isFile()&&name.matches(java.util.regex.Pattern.quote(item.id)+"-[0-9]+\\.ink(?:\\.clear)?(?:\\.(?:bak|new))?"))addIfExists(moves,file,new File(new File(transactionRoot,"ink"),name));}
            File attachmentRoot=LibraryStore.attachmentsDirectory(context);
            addIfExists(moves,new File(attachmentRoot,item.id),new File(transactionRoot,"attachments"));
            if(!item.attachment.isEmpty()){
                File attachment=safeChild(attachmentRoot,item.attachment);if(attachment.exists()&&!isCoveredBy(moves,attachment))addIfExists(moves,attachment,new File(new File(transactionRoot,"single-attachment"),attachment.getName()));
            }
            File covers=new File(LibraryStore.directory(context),"covers");addIfExists(moves,new File(covers,item.id+".png"),new File(transactionRoot,"cover.png"));
            writeManifest(files,transactionRoot,item.id,moves);
            for(Move move:moves)move.perform();
            if(item.isNote())NoteStore.invalidate(context,item.id);
            else{ReaderStore.invalidate(files,item.id);InkStore.invalidateDocument(item.id);}
            return new Deletion(context,item.id,item.isNote(),transactionRoot,moves);
        }catch(IOException|RuntimeException problem){
            try{
                IOException failure=asIOException("删除暂存失败",problem);
                IOException rollbackFailure=rollbackMoves(moves);
                if(rollbackFailure!=null){
                    IOException preserved=new IOException("删除暂存失败且自动回滚未完成；未恢复文件保留在 "+transactionRoot.getAbsolutePath(),failure);
                    preserved.addSuppressed(rollbackFailure);
                    throw preserved;
                }
                try{deleteTree(transactionRoot);}catch(IOException|RuntimeException cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }finally{unregister(transactionRoot);}
        }
    }

    public static final class RecoveryResult {
        public final int rolledBack,cleaned,retained;
        public final List<String> warnings;
        RecoveryResult(int rolledBack,int cleaned,int retained,List<String> warnings){this.rolledBack=rolledBack;this.cleaned=cleaned;this.retained=retained;this.warnings=Collections.unmodifiableList(new ArrayList<>(warnings));}
        public boolean hasWarnings(){return !warnings.isEmpty();}
    }

    /**
     * Recovers delete transactions left by process death. The library index is the commit point:
     * an item still present is rolled back; an absent item has its staged payload cleaned.
     * Malformed or ambiguous transactions are retained for manual recovery and never deleted.
     */
    public static RecoveryResult recoverPending(Context context)throws IOException {
        if(context==null)throw new IOException("恢复删除事务参数无效");
        LibraryStore.State state=LibraryStore.load(context);
        File root=new File(LibraryStore.directory(context),"delete-transactions");
        File[] transactions=root.listFiles();
        if(transactions==null)return new RecoveryResult(0,0,0,Collections.emptyList());
        if(!state.allowSave||!state.damaged.isEmpty())throw new IOException("资料库索引含有损坏记录，删除恢复目录已保留");
        Set<String> live=new HashSet<>();for(LibraryItem item:state.items)live.add(item.id);
        int rolledBack=0,cleaned=0,retained=0;ArrayList<String>warnings=new ArrayList<>();
        for(File transaction:transactions){
            if(isActive(transaction))continue;
            if(!transaction.isDirectory()){retained++;warnings.add("发现未知的删除事务文件："+transaction.getName());continue;}
            try{
                RecoveryPlan plan=readManifest(context.getFilesDir(),transaction);
                if(live.contains(plan.documentId)){
                    boolean missing=false;
                    for(Move move:plan.moves){boolean source=move.source.exists(),target=move.target.exists();if(target)move.moved=true;else if(!source)missing=true;}
                    if(missing)throw new IOException("删除事务缺少原文件与暂存副本");
                    IOException failure=rollbackMoves(plan.moves);if(failure!=null)throw failure;
                    NoteStore.revive(context,plan.documentId);
                    ReaderStore.revive(context.getFilesDir(),plan.documentId);
                    InkStore.reviveDocument(plan.documentId);
                    deleteTree(transaction);STAGED_IDS.remove(plan.documentId);rolledBack++;
                }else{deleteTree(transaction);STAGED_IDS.remove(plan.documentId);cleaned++;}
            }catch(IOException|RuntimeException failure){retained++;warnings.add(transaction.getName()+"："+safeMessage(failure));}
        }
        File[] remaining=root.listFiles();if(remaining!=null&&remaining.length==0)root.delete();
        return new RecoveryResult(rolledBack,cleaned,retained,warnings);
    }

    public static final class Deletion {
        /** A commit can be logically complete while physical cleanup remains retryable. */
        public static final class CommitResult {
            public final boolean cleanupComplete;
            public final File pendingDirectory;
            public final IOException cleanupFailure;
            CommitResult(boolean cleanupComplete,File pendingDirectory,IOException cleanupFailure){this.cleanupComplete=cleanupComplete;this.pendingDirectory=pendingDirectory;this.cleanupFailure=cleanupFailure;}
        }

        private final Context context;private final String documentId;private final boolean note;
        private final File root;private final ArrayList<Move> moves;
        private boolean committed,resourcesRestored;
        private IOException cleanupFailure;
        Deletion(Context context,String documentId,boolean note,File root,ArrayList<Move> moves){this.context=context;this.documentId=documentId;this.note=note;this.root=root;this.moves=moves;}

        /**
         * Marks the deletion committed before attempting best-effort physical cleanup. Cleanup
         * failure does not turn a successfully saved index deletion back into a rollback request.
         * Callers that care about reclaimed space can inspect the result and call retryCleanup().
         */
        public synchronized CommitResult commit(){
            if(resourcesRestored)throw new IllegalStateException("删除事务已经回滚");
            register(root);
            try{committed=true;return cleanupCommitted();}
            finally{STAGED_IDS.remove(documentId);unregister(root);}
        }

        /** Retries only the cleanup of an already committed deletion; it never restores files. */
        public synchronized CommitResult retryCleanup(){
            if(!committed)throw new IllegalStateException("删除事务尚未提交");
            register(root);
            try{return cleanupCommitted();}
            finally{unregister(root);}
        }

        /**
         * Restores staged files. If any move cannot be restored, the transaction directory and
         * its remaining unique copies are preserved and this method can safely be called again.
         * Once commit() has established the logical commit point, rollback is intentionally a
         * no-op so a cleanup error cannot cause a false rollback.
         */
        public synchronized void rollback()throws IOException {
            if(committed)return;
            register(root);
            try{
                if(!resourcesRestored){
                    IOException failure=rollbackMoves(moves);
                    if(failure!=null)throw new IOException("删除回滚未完成；未恢复文件保留在 "+root.getAbsolutePath()+"，可再次调用 rollback() 重试",failure);
                    if(note)NoteStore.revive(context,documentId);
                    else{ReaderStore.revive(context.getFilesDir(),documentId);InkStore.reviveDocument(documentId);}
                    resourcesRestored=true;
                }
                try{deleteTree(root);cleanupFailure=null;}
                catch(IOException|RuntimeException problem){cleanupFailure=asIOException("无法清理回滚事务目录",problem);throw new IOException("文件已恢复，但事务目录清理未完成；目录保留在 "+root.getAbsolutePath()+"，可再次调用 rollback() 重试清理",cleanupFailure);}
            }finally{if(resourcesRestored)STAGED_IDS.remove(documentId);unregister(root);}
        }

        /** Test/process-owner hook: leaves durable files for recoverPending() to adjudicate. */
        synchronized void abandonForRecovery(){unregister(root);}

        public synchronized boolean isCommitted(){return committed;}
        public synchronized boolean isRolledBack(){return resourcesRestored;}
        public synchronized boolean hasPendingCleanup(){return (committed||resourcesRestored)&&root.exists();}
        /** Directory retained after an incomplete rollback or committed cleanup. */
        public synchronized File recoveryDirectory(){return root;}

        private CommitResult cleanupCommitted(){
            try{deleteTree(root);cleanupFailure=null;}
            catch(IOException|RuntimeException problem){cleanupFailure=asIOException("无法清理已提交的删除事务",problem);}
            boolean complete=!root.exists();
            if(complete)cleanupFailure=null;
            return new CommitResult(complete,complete?null:root,cleanupFailure);
        }
    }

    private static File beginTransaction(Context context)throws IOException{
        synchronized(ACTIVE_LOCK){
            File transaction=new File(new File(LibraryStore.directory(context),"delete-transactions"),UUID.randomUUID().toString());
            if(!transaction.mkdirs())throw new IOException("无法创建删除事务");
            try{ACTIVE_TRANSACTIONS.add(transaction.getCanonicalPath());return transaction;}
            catch(IOException failure){transaction.delete();throw failure;}
        }
    }
    private static void register(File transaction){if(transaction==null||!transaction.exists())return;try{synchronized(ACTIVE_LOCK){ACTIVE_TRANSACTIONS.add(transaction.getCanonicalPath());}}catch(IOException ignored){}}
    private static void unregister(File transaction){if(transaction==null)return;try{synchronized(ACTIVE_LOCK){ACTIVE_TRANSACTIONS.remove(transaction.getCanonicalPath());}}catch(IOException ignored){}}
    private static boolean isActive(File transaction){try{synchronized(ACTIVE_LOCK){return ACTIVE_TRANSACTIONS.contains(transaction.getCanonicalPath());}}catch(IOException ignored){return true;}}

    private static void addIfExists(ArrayList<Move> moves,File source,File target){if(source.exists())moves.add(new Move(source,target));}
    private static boolean isCoveredBy(List<Move> moves,File candidate)throws IOException {String path=candidate.getCanonicalPath();for(Move move:moves){String parent=move.source.getCanonicalPath();if(path.equals(parent)||path.startsWith(parent+File.separator))return true;}return false;}
    private static File safeChild(File root,String relative)throws IOException {File child=new File(root,relative);String base=root.getCanonicalPath(),path=child.getCanonicalPath();if(!path.startsWith(base+File.separator))throw new IOException("附件路径无效");return child;}
    private static final class Move {
        final File source,target;boolean moved;
        Move(File source,File target){this.source=source;this.target=target;}
        void perform()throws IOException {File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法建立删除暂存目录");if(target.exists()||!source.renameTo(target))throw new IOException("无法暂存 "+source.getName());moved=true;}
        void rollback()throws IOException {if(!moved||!target.exists())return;File parent=source.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法恢复删除项目");if(source.exists()||!target.renameTo(source))throw new IOException("无法回滚 "+source.getName());moved=false;}
    }
    private static final class RecoveryPlan {
        final String documentId;final ArrayList<Move> moves;
        RecoveryPlan(String documentId,ArrayList<Move> moves){this.documentId=documentId;this.moves=moves;}
    }

    private static void writeManifest(File files,File transaction,String documentId,List<Move> moves)throws IOException {
        if(moves.size()>MAX_MANIFEST_MOVES)throw new IOException("删除事务文件数量过多");
        File manifest=new File(transaction,MANIFEST_NAME),temporary=new File(transaction,MANIFEST_NAME+".new");
        boolean complete=false;
        try(FileOutputStream file=new FileOutputStream(temporary);DataOutputStream output=new DataOutputStream(new BufferedOutputStream(file))){
            output.writeInt(MANIFEST_MAGIC);output.writeInt(MANIFEST_VERSION);output.writeUTF(documentId);output.writeInt(moves.size());
            for(Move move:moves){output.writeUTF(relative(files,move.source));output.writeUTF(relative(transaction,move.target));}
            output.flush();file.getFD().sync();complete=true;
        }finally{if(!complete)temporary.delete();}
        if(manifest.exists()||!temporary.renameTo(manifest)){temporary.delete();throw new IOException("无法提交删除事务清单");}
    }

    private static RecoveryPlan readManifest(File files,File transaction)throws IOException {
        File manifest=new File(transaction,MANIFEST_NAME);if(!manifest.isFile())throw new IOException("缺少删除事务清单");
        try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(manifest)))){
            if(input.readInt()!=MANIFEST_MAGIC||input.readInt()!=MANIFEST_VERSION)throw new IOException("删除事务清单版本无效");
            String id=input.readUTF();if(!LibraryItem.isUuid(id))throw new IOException("删除事务文档编号无效");
            int count=input.readInt();if(count<0||count>MAX_MANIFEST_MOVES)throw new IOException("删除事务文件数量无效");
            ArrayList<Move> moves=new ArrayList<>();Set<String>sources=new HashSet<>(),targets=new HashSet<>();
            for(int index=0;index<count;index++){
                File source=contained(files,input.readUTF()),target=contained(transaction,input.readUTF());
                if(!sources.add(source.getCanonicalPath())||!targets.add(target.getCanonicalPath()))throw new IOException("删除事务清单包含重复路径");
                moves.add(new Move(source,target));
            }
            if(input.read()!=-1)throw new IOException("删除事务清单含多余内容");
            return new RecoveryPlan(id,moves);
        }catch(EOFException truncated){throw new IOException("删除事务清单不完整",truncated);}
    }

    private static String relative(File root,File child)throws IOException {
        String base=root.getCanonicalPath(),path=child.getCanonicalPath();String prefix=base+File.separator;
        if(!path.startsWith(prefix))throw new IOException("删除事务路径越界");
        String value=path.substring(prefix.length()).replace(File.separatorChar,'/');validateRelative(value);return value;
    }
    private static File contained(File root,String relative)throws IOException {validateRelative(relative);File child=new File(root,relative.replace('/',File.separatorChar));String base=root.getCanonicalPath(),path=child.getCanonicalPath();if(!path.startsWith(base+File.separator))throw new IOException("删除事务路径越界");return child;}
    private static void validateRelative(String value)throws IOException {if(value==null||value.isEmpty()||value.length()>1024||value.startsWith("/")||value.startsWith("\\")||value.indexOf('\\')>=0||value.indexOf(':')>=0)throw new IOException("删除事务相对路径无效");for(String segment:value.split("/",-1))if(segment.isEmpty()||".".equals(segment)||"..".equals(segment))throw new IOException("删除事务相对路径无效");}
    private static IOException rollbackMoves(ArrayList<Move> moves){IOException failure=null;ArrayList<Move> reverse=new ArrayList<>(moves);Collections.reverse(reverse);for(Move move:reverse)try{move.rollback();}catch(IOException|RuntimeException problem){IOException e=asIOException("无法恢复删除项目",problem);if(failure==null)failure=e;else failure.addSuppressed(e);}return failure;}
    private static IOException asIOException(String message,Throwable problem){return problem instanceof IOException?(IOException)problem:new IOException(message,problem);}
    private static String safeMessage(Throwable problem){String message=problem==null?null:problem.getMessage();return message==null||message.trim().isEmpty()?"无法恢复删除事务":message;}
    private static void deleteTree(File file)throws IOException {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("无法读取删除暂存目录");for(File child:children)deleteTree(child);}if(!file.delete())throw new IOException("无法清理 "+file.getName());}
}

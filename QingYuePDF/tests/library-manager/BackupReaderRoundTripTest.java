package cn.qingyue.pdf;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Exercises the production backup adapter, reader stores and KEEP_BOTH restore together. */
public final class BackupReaderRoundTripTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    public static void main(String[] args) {
        try {
            runChecks();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            // NoteStore owns a non-daemon process worker. An assertion/IO failure must not
            // leave this desktop test process alive after its main thread has terminated.
            System.exit(1);
        }
    }
    private static void runChecks() throws Exception {
        File root=Files.createTempDirectory("backup-reader-").toFile();
        Context context=new Context(root);
        LibraryItem item=new LibraryItem();item.id=ID;item.name="reader";item.pages=2;
        Files.write(new File(root,ID+".pdf").toPath(),new byte[]{1,2,3});
        ReaderDocument metadata=new ReaderDocument();metadata.addBookmark(1,"saved bookmark");
        metadata.addMarkup(1,0xff123456,"highlight",Arrays.asList(new ReaderDocument.Box(0.1f,0.2f,0.3f,0.4f)));
        long readerRevision=ReaderStore.stage(root,ID,metadata);
        InkPage.Stroke stroke=new InkPage.Stroke(0xff000000,0.01f,new float[]{0.1f,0.2f,0.3f,0.4f});
        long inkRevision=InkStore.stage(ID+"-1",Collections.singletonList(stroke));
        InkStore.prepareClear(ID+"-1",Collections.singletonList(stroke));
        LibraryStore.save(context,Collections.singletonList(item));
        byte[] corrected=("QY-OCR-1\n"+repeat('a',64)+"\n中文 English correction").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OcrSidecar.save(context,ID,1,repeat('a',64),"中文 English correction");
        File archive=new File(root,"roundtrip.qyb");
        BackupManager.createSnapshot(context,Collections.singletonList(item),archive,
                BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        check(!ReaderStore.hasPending(root,ID)&&!InkStore.pending.containsKey(ID+"-1"),"snapshot did not drain pending state");
        check(!InkStore.saveStaged(root,ID+"-1",inkRevision),"old ink callback overwrote snapshot");
        ReaderStore.saveStaged(root,ID,readerRevision);
        BackupManager.RestoreResult result=BackupManager.restore(context,archive,
                BackupManager.ConflictPolicy.KEEP_BOTH,false,BackupManager.NEVER_CANCEL,
                BackupManager.NO_PROGRESS);
        check(result.conflictCopies==1,"expected KEEP_BOTH collision");
        LibraryItem copy=null;for(LibraryItem candidate:LibraryStore.load(context).items)
            if(!ID.equals(candidate.id))copy=candidate;
        check(copy!=null,"missing restored copy");
        check(ReaderStore.load(root,copy.id).bookmarks().size()==1,"bookmark lost");
        check(ReaderStore.load(root,copy.id).markups().size()==1,"highlight lost");
        check(InkStore.load(root,copy.id+"-1").size()==1,"ink lost");
        check(InkStore.loadClearRecovery(root,copy.id+"-1").size()==1,"clear recovery lost");
        File restoredSidecar=new File(new File(new File(root,"recognition"),copy.id),"page-1.txt");
        check(Arrays.equals(Files.readAllBytes(restoredSidecar.toPath()),corrected),"OCR sidecar lost on KEEP_BOTH restore");
        TrashManager.Deletion deletion=TrashManager.stagePermanentDelete(context,copy);
        check(!restoredSidecar.exists(),"OCR sidecar not staged");
        try{OcrSidecar.save(context,copy.id,1,repeat('a',64),"late write");throw new AssertionError("OCR revived staged delete");}
        catch(java.io.IOException expected){}
        check(!ReaderStore.file(root,copy.id).exists(),"reader sidecar not staged");
        check(!InkStore.clearFile(root,copy.id+"-1").exists(),"clear sidecar not staged");
        deletion.rollback();
        check(ReaderStore.load(root,copy.id).bookmarks().size()==1,"reader rollback lost");
        check(InkStore.loadClearRecovery(root,copy.id+"-1").size()==1,"clear rollback lost");
        check(Arrays.equals(Files.readAllBytes(restoredSidecar.toPath()),corrected),"OCR rollback lost");
        long staleInk=InkStore.stage(copy.id+"-1",Collections.singletonList(stroke));
        long staleReader=ReaderStore.stage(root,copy.id,metadata);
        TrashManager.Deletion committed=TrashManager.stagePermanentDelete(context,copy);
        check(!InkStore.saveStaged(root,copy.id+"-1",staleInk),"deleted ink callback revived file");
        ReaderStore.saveStaged(root,copy.id,staleReader);
        check(!ReaderStore.file(root,copy.id).exists(),"deleted reader callback revived file");
        committed.commit();
        check(!InkStore.file(root,copy.id+"-1").exists(),"deleted ink survived commit");
        check(!restoredSidecar.exists(),"deleted OCR sidecar survived commit");
        noteDeleteQueuedSave();
        noteTextBackup();
        folderTreeBackup();
        deletedTrashFolderBackup();
        System.out.println("BackupReaderRoundTripTest passed: pending snapshot, KEEP_BOTH, reader/ink/clear, delete rollback");
    }
    private static void folderTreeBackup()throws Exception{
        File root=Files.createTempDirectory("folder-backup-").toFile();Context context=new Context(root);
        LibraryFolder parent=new LibraryFolder("66666666-6666-4666-8666-666666666666","","学习/原名");
        LibraryFolder child=new LibraryFolder("77777777-7777-4777-8777-777777777777",parent.id,"课本");
        LibraryFolder empty=new LibraryFolder("88888888-8888-4888-8888-888888888888",child.id,"空文件夹");
        LibraryItem item=new LibraryItem();item.id=ID;item.name="book";item.folder=child.name;item.folderId=child.id;
        Files.write(new File(root,ID+".pdf").toPath(),new byte[]{1,2,3});
        LibraryStore.save(context,Collections.singletonList(item),Arrays.asList(parent,child,empty));
        File archive=new File(root,"folders.qyb");
        BackupManager.createSnapshot(context,Collections.singletonList(item),archive,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        BackupManager.restore(context,archive,BackupManager.ConflictPolicy.KEEP_BOTH,false,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        LibraryStore.State after=LibraryStore.load(context);
        check(after.allowSave&&after.folders.size()==6,"folder tree or empty folder lost");
        LibraryItem copy=null;for(LibraryItem entry:after.items)if(!ID.equals(entry.id))copy=entry;
        check(copy!=null&&!child.id.equals(copy.folderId),"restored document did not map to copied folder");
        LibraryFolder copyChild=null;for(LibraryFolder folder:after.folders)if(folder.id.equals(copy.folderId))copyChild=folder;
        check(copyChild!=null&&!parent.id.equals(copyChild.parentId),"restored parent link points to original tree");
        boolean emptyFound=false;for(LibraryFolder folder:after.folders)
            if(folder.parentId.equals(copyChild.id)&&folder.name.equals("空文件夹"))emptyFound=true;
        check(emptyFound,"nested empty folder lost");
        File onlyRoot=Files.createTempDirectory("empty-folder-backup-").toFile();Context only=new Context(onlyRoot);
        LibraryStore.save(only,Collections.emptyList(),Collections.singletonList(parent));
        File first=new File(onlyRoot,"before.qyb");
        String before=BackupManager.createSnapshot(only,Collections.emptyList(),first,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS).stableFingerprint;
        parent.name="新的空目录";LibraryStore.save(only,Collections.emptyList(),Collections.singletonList(parent));
        File second=new File(onlyRoot,"after.qyb");
        String changed=BackupManager.createSnapshot(only,Collections.emptyList(),second,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS).stableFingerprint;
        check(!before.equals(changed),"empty folder metadata excluded from snapshot fingerprint");
        BackupManager.restore(only,first,BackupManager.ConflictPolicy.KEEP_BOTH,false,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        check(LibraryStore.load(only).folders.size()==2,"folder-only archive did not restore");
    }
    private static void deletedTrashFolderBackup()throws Exception{
        File root=Files.createTempDirectory("deleted-trash-folder-backup-").toFile();Context context=new Context(root);
        LibraryFolder parent=new LibraryFolder("66666666-6666-4666-8666-666666666666","","父级");
        LibraryFolder child=new LibraryFolder("77777777-7777-4777-8777-777777777777",parent.id,"已删除子目录");
        LibraryItem item=new LibraryItem();item.id=ID;item.name="trashed book";item.folder=child.name;item.folderId=child.id;
        byte[] original=new byte[]{7,8,9};Files.write(new File(root,ID+".pdf").toPath(),original);
        LibraryStore.save(context,Collections.singletonList(item),Arrays.asList(parent,child));
        LibraryStore.State trash=LibraryStore.mutate(context,entries->TrashManager.moveToTrash(entries.get(0),1234));
        LibraryStore.State withoutChild=LibraryStore.deleteEmptyFolder(context,child.id,trash.revision);
        File nestedArchive=new File(root,"parent-destination.qyb");
        BackupManager.createSnapshot(context,withoutChild.items,nestedArchive,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        File restoredRoot=Files.createTempDirectory("restored-trash-parent-").toFile();Context restoredContext=new Context(restoredRoot);
        BackupManager.restore(restoredContext,nestedArchive,BackupManager.ConflictPolicy.KEEP_BOTH,false,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        LibraryStore.State restored=LibraryStore.load(restoredContext);
        check(restored.allowSave&&restored.folders.size()==1,"deleted child folder reappeared in backup restore");
        check(restored.items.size()==1&&restored.items.get(0).isTrashed(),"backup restore discarded or untrashed document");
        check(restored.folders.get(0).id.equals(restored.items.get(0).folderId),"backup restore lost parent destination mapping");
        check(restored.items.get(0).trashedAt==1234,"backup changed trash timestamp");
        String restoredId=restored.items.get(0).id;
        check(Arrays.equals(original,Files.readAllBytes(new File(restoredRoot,restoredId+".pdf").toPath())),"backup lost trash payload");
        LibraryStore.State active=LibraryStore.mutate(restoredContext,entries->TrashManager.restore(entries.get(0),2000));
        check(!active.items.get(0).isTrashed()&&active.folders.get(0).id.equals(active.items.get(0).folderId),"trash restore after backup did not return to parent");
        // Removing that final parent clears both new and legacy folder fields in the archive.
        LibraryStore.State withoutParent=LibraryStore.deleteEmptyFolder(context,parent.id,withoutChild.revision);
        File rootArchive=new File(root,"unfiled-destination.qyb");
        BackupManager.createSnapshot(context,withoutParent.items,rootArchive,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        File unfiledRoot=Files.createTempDirectory("restored-trash-unfiled-").toFile();Context unfiledContext=new Context(unfiledRoot);
        BackupManager.restore(unfiledContext,rootArchive,BackupManager.ConflictPolicy.KEEP_BOTH,false,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        LibraryStore.State unfiled=LibraryStore.load(unfiledContext);
        check(unfiled.allowSave&&unfiled.folders.isEmpty(),"backup recreated deleted legacy folder");
        check(unfiled.items.size()==1&&unfiled.items.get(0).isTrashed(),"unfiled trash item missing after backup");
        check(unfiled.items.get(0).folderId.isEmpty()&&unfiled.items.get(0).folder.isEmpty(),"unfiled backup retained stale folder reference");
        LibraryStore.State unfiledActive=LibraryStore.mutate(unfiledContext,entries->TrashManager.restore(entries.get(0),3000));
        check(!unfiledActive.items.get(0).isTrashed(),"unfiled backup document cannot be restored");
        check(Arrays.equals(original,Files.readAllBytes(new File(unfiledRoot,unfiledActive.items.get(0).id+".pdf").toPath())),"unfiled restored payload changed");
    }
    private static void noteDeleteQueuedSave()throws Exception{
        File root=Files.createTempDirectory("note-delete-").toFile();Context context=new Context(root);
        String id="33333333-3333-4333-8333-333333333333";
        NoteDocument note=NoteStore.create(context,id,"blank","A4");
        TrashManager.Deletion deletion;
        synchronized(NoteStore.class){
            NoteStore.saveAsync(context,id,note,null);
            LibraryItem item=new LibraryItem();item.id=id;item.name="note";item.type="note";
            deletion=TrashManager.stagePermanentDelete(context,item);
        }
        Thread.sleep(100);
        check(!new File(new File(root,"notes"),id).exists(),"queued note save revived staged delete");
        deletion.rollback();
        check(NoteStore.load(context,id)!=null,"note rollback did not revive store");
    }
    private static void noteTextBackup()throws Exception{
        File root=Files.createTempDirectory("note-text-backup-").toFile();Context context=new Context(root);
        String id="44444444-4444-4444-8444-444444444444";
        String sourceId="55555555-5555-4555-8555-555555555555";
        LibraryItem source=new LibraryItem();source.id=sourceId;source.name="source PDF";source.pages=2;
        Files.write(new File(root,sourceId+".pdf").toPath(),new byte[]{1,2,3});
        NoteDocument note=NoteStore.create(context,id,"blank","A4");
        String uri=ReaderSourceLink.page(sourceId,"source PDF",1).toUri();
        NoteDocument.Item text=new NoteDocument.Item(note.newId(),NoteDocument.TEXT,0xff336699,22,
                new double[]{30,40,230,110},uri,"备份中文 English\nline 2");
        note.add(text);note.addPage();
        note.add(new NoteDocument.Item(note.newId(),NoteDocument.TEXT,0xff336699,22,
                new double[]{30,40,230,110},uri,"第二页来源"));note.selectPage(0);NoteStore.save(context,id,note);
        LibraryItem item=new LibraryItem();item.id=id;item.name="text note";item.type="note";
        LibraryStore.save(context,Arrays.asList(item,source));
        File archive=new File(root,"text.qyb");BackupManager.createSnapshot(context,
                Arrays.asList(item,source),archive,BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        BackupManager.restore(context,archive,BackupManager.ConflictPolicy.KEEP_BOTH,false,
                BackupManager.NEVER_CANCEL,BackupManager.NO_PROGRESS);
        LibraryItem copy=null,sourceCopy=null;for(LibraryItem candidate:LibraryStore.load(context).items){
            if(candidate.isNote()&&!id.equals(candidate.id))copy=candidate;
            if(!candidate.isNote()&&!sourceId.equals(candidate.id))sourceCopy=candidate;
        }
        check(copy!=null,"text note copy missing");
        NoteDocument restored=NoteStore.load(context,copy.id);
        check(restored!=null&&restored.items().size()==1&&restored.items().get(0).text.equals(text.text),
                "v2 text note lost on backup restore");
        check(sourceCopy!=null,"source PDF copy missing");
        check(ReaderSourceLink.parse(restored.items().get(0).image).documentId.equals(sourceCopy.id),
                "KEEP_BOTH note source still points at original PDF");
        check(restored.pageCount()==2&&restored.currentPage()==0,"multi-page backup or active page lost");
        check(ReaderSourceLink.parse(restored.pageItems(1).get(0).image).documentId.equals(sourceCopy.id),
                "second-page source was not remapped on KEEP_BOTH restore");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
}

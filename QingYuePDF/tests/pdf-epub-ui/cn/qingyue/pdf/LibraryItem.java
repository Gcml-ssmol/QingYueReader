package cn.qingyue.pdf;
public final class LibraryItem {
    public String id,hash="";public int pages=5;
    public boolean isNote(){return false;}
    public boolean isTrashed(){return false;}
    public static boolean isUuid(String value){return value!=null&&value.matches("[0-9a-f-]{36}");}
}

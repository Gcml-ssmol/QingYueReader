package cn.qingyue.pdf;
import android.content.Context;import java.util.*;
public final class LibraryStore {
    static final ArrayList<LibraryItem> ITEMS=new ArrayList<>();
    public static State load(Context app){return new State();}
    public static final class State {public final ArrayList<LibraryItem> items=new ArrayList<>(ITEMS);}
}

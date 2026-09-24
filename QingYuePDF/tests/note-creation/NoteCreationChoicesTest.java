package cn.qingyue.pdf;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

public final class NoteCreationChoicesTest {
    private static final class MemoryPreferences implements SharedPreferences, SharedPreferences.Editor {
        final Map<String,String> values=new HashMap<>();
        @Override public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
        @Override public Editor edit(){return this;}
        @Override public Editor putString(String key,String value){values.put(key,value);return this;}
        @Override public void apply(){}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        MemoryPreferences p=new MemoryPreferences();
        NoteCreationChoices fresh=new NoteCreationChoices(p);
        check("infinite".equals(fresh.size)&&"blank".equals(fresh.template),"fresh creation must default to infinite blank draft");
        fresh.paged();fresh.size("B5");fresh.template("grid");fresh.draft();
        check(p.values.isEmpty(),"closing dialog without submit must not write preferences");
        final int[] calls={0};
        check(!fresh.submit("  ",(name,template,size)->calls[0]++),"empty title must fail");
        check(calls[0]==0&&p.values.isEmpty(),"empty title must not create or save");
        check(fresh.submit("  草稿  ",(name,template,size)->{
            calls[0]++;check("草稿".equals(name)&&"grid".equals(template)&&"infinite".equals(size),"create callback values");
        }),"valid draft submit");
        check(calls[0]==1,"single creation callback");
        NoteCreationChoices restored=new NoteCreationChoices(p);
        check("infinite".equals(restored.size)&&"grid".equals(restored.template),"draft preference restored");
        restored.paged();check("B5".equals(restored.size),"last finite size restored");
        restored.submit("分页",(name,template,size)->check("B5".equals(size),"paged callback size"));
        NoteCreationChoices paged=new NoteCreationChoices(p);
        check("B5".equals(paged.size),"paged preference restored");
        p.values.put("size","bad");p.values.put("template","bad");
        NoteCreationChoices invalid=new NoteCreationChoices(p);
        check("A4".equals(invalid.size)&&"blank".equals(invalid.template),"invalid stored values must fall back safely");
    }
}

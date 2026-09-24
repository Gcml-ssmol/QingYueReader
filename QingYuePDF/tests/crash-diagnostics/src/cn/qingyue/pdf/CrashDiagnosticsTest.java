package cn.qingyue.pdf;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public final class CrashDiagnosticsTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static final class Prefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
        public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
        public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
        public Editor edit(){return new Editor(){final Map<String,Object> pending=new HashMap<>();
            public Editor putString(String k,String v){pending.put(k,v);return this;}
            public Editor putLong(String k,long v){pending.put(k,v);return this;}
            public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
            public boolean commit(){values.putAll(pending);return true;}public void apply(){commit();}};}
    }
    private static final class App extends Application {
        final File files;final Prefs prefs=new Prefs();List<ApplicationExitInfo> exits=Collections.emptyList();String version="1.9.2";
        App(File files){this.files=files;files.mkdirs();}
        public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        public File getFilesDir(){return files;}
        public String getPackageName(){return "cn.qingyue.pdf";}
        public PackageManager getPackageManager(){return new PackageManager(){public PackageInfo getPackageInfo(String name,int flags){PackageInfo p=new PackageInfo();p.versionName=version;return p;}};}
        public Object getSystemService(String name){return new ActivityManager(){public List<ApplicationExitInfo> getHistoricalProcessExitReasons(String pkg,int pid,int max){return exits;}};}
    }
    private static final Method WRITE,INSPECT;
    static {try {WRITE=CrashDiagnostics.class.getDeclaredMethod("write",Context.class,String.class,Thread.class,Throwable.class,boolean.class,String.class);WRITE.setAccessible(true);INSPECT=CrashDiagnostics.class.getDeclaredMethod("inspectPreviousExit",Context.class,String.class,String.class,long.class);INSPECT.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static void write(App a,boolean fatal,Throwable e) throws Exception {WRITE.invoke(null,a,fatal?"未捕获崩溃":"已处理异常",Thread.currentThread(),e,fatal,"导航切换");}
    private static void inspect(App a,String stage,String version,long session) throws Exception {INSPECT.invoke(null,a,stage,version,session);}
    private static App app(File root,String name){return new App(new File(root,name));}
    private static Throwable secret() {Throwable t=new IllegalStateException("private /storage/emulated/0/secret.pdf");t.setStackTrace(new StackTraceElement[]{new StackTraceElement("Reader","open","secret.pdf",42)});return t;}
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);
        App a=app(root,"privacy");Throwable cause=secret();Throwable head=new RuntimeException("/private/note.txt",cause);
        StackTraceElement[] frames=new StackTraceElement[40];Arrays.fill(frames,new StackTraceElement("Reader","draw","Reader.java",7));head.setStackTrace(frames);
        CrashDiagnostics.record(a,"/private/tag.pdf",head);String report=CrashDiagnostics.read(a);
        check(report.contains("java.lang.RuntimeException")&&report.contains("java.lang.IllegalStateException"),"cause types absent");
        check(!report.contains("/private/")&&!report.contains("/storage/")&&!report.contains("secret.pdf"),"private message/path leaked");
        check(report.split("  at ",-1).length-1<=33,"stack frames unbounded");
        check(report.length()<=12000&&CrashDiagnostics.hasUnread(a),"report limit or unread state");
        Throwable chain=new Exception("tail6");for(int i=5;i>=1;i--)chain=new Exception("tail"+i,chain);
        CrashDiagnostics.markRead(a);CrashDiagnostics.record(a,"chain",chain);
        check(CrashDiagnostics.read(a).split("原因: ",-1).length-1==4,"cause chain not bounded at five throwables");
        check(!CrashDiagnostics.claimNewFatalPrompt(a),"handled report prompted");
        write(a,true,secret());check(CrashDiagnostics.hasUnreadFatal(a),"fatal did not replace handled report");
        String fatal=CrashDiagnostics.read(a);CrashDiagnostics.record(a,"tag",new Exception("later"));
        check(fatal.equals(CrashDiagnostics.read(a)),"handled replaced unread fatal");
        check(CrashDiagnostics.claimNewFatalPrompt(a)&&!CrashDiagnostics.claimNewFatalPrompt(a),"prompt not exactly once");
        CrashDiagnostics.markRead(a);check(!CrashDiagnostics.claimNewFatalPrompt(a),"read report prompted");
        write(a,true,new Exception("next"));check(CrashDiagnostics.claimNewFatalPrompt(a),"new fatal did not prompt");

        Field started=CrashDiagnostics.class.getDeclaredField("processStartedAt");started.setAccessible(true);long now=started.getLong(null);
        App normal=app(root,"normal");
        normal.exits=Arrays.asList(new ApplicationExitInfo(normal.getPackageName(),now-100,ApplicationExitInfo.REASON_CRASH),new ApplicationExitInfo(normal.getPackageName(),now-50,ApplicationExitInfo.REASON_USER_REQUESTED));
        inspect(normal,"阅读器","1.8.0",now-1000);check(!CrashDiagnostics.hasUnread(normal),"earlier crash reported after normal exit");
        check(normal.prefs.getLong("checkedExit",0)==now-50,"latest exit not checked");
        App old=app(root,"old");old.exits=Collections.singletonList(new ApplicationExitInfo(old.getPackageName(),now-2000,ApplicationExitInfo.REASON_CRASH));
        inspect(old,"阅读器","1.8.0",now-1000);check(!CrashDiagnostics.hasUnread(old),"exit before previous session attributed");
        App previous=app(root,"previous");previous.exits=Collections.singletonList(new ApplicationExitInfo(previous.getPackageName(),now-100,ApplicationExitInfo.REASON_ANR));
        inspect(previous,"导航切换","1.8.0",now-1000);String system=CrashDiagnostics.read(previous);
        check(system.contains("系统记录：无响应")&&system.contains("版本: 1.8.0")&&system.contains("阶段: 导航切换"),"previous exit metadata wrong");
        check(!system.contains("1.9.2"),"current version used for previous exit");
        a.exits=Collections.singletonList(new ApplicationExitInfo(a.getPackageName(),now-100,ApplicationExitInfo.REASON_CRASH));
        String latestFatal=CrashDiagnostics.read(a);
        inspect(a,"阅读器","1.8.0",now-1000);check(latestFatal.equals(CrashDiagnostics.read(a))&&CrashDiagnostics.hasUnreadFatal(a),"system check replaced unread fatal");

        App delegated=app(root,"delegated");Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
        final int[] calls={0};Thread.UncaughtExceptionHandler prior=(thread,error)->calls[0]++;
        try {Thread.setDefaultUncaughtExceptionHandler(prior);Build.VERSION.SDK_INT=29;CrashDiagnostics.install(delegated);
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),secret());
            check(calls[0]==1&&CrashDiagnostics.hasUnreadFatal(delegated),"default handler not delegated");
        }finally{Thread.setDefaultUncaughtExceptionHandler(original);}
        QingYueApplication startup=new QingYueApplication();startup.testBaseContext=app(root,"startup");
        try{Thread.setDefaultUncaughtExceptionHandler(prior);startup.onCreate();
            check(QingYueApplication.instance()==startup,"real Application instance not installed");
            check(Thread.getDefaultUncaughtExceptionHandler()!=prior,"real Application skipped crash diagnostics");
            check(PdfPageInsertion.startCalls==1&&PdfPageInsertion.lastContext==startup,"real Application skipped recovery startup");
        }finally{Thread.setDefaultUncaughtExceptionHandler(original);}
        System.out.println("CrashDiagnosticsTest passed: privacy, bounded causes, fatal precedence, prompts, exit attribution, handler delegation, real Application recovery startup");
    }
}

package cn.qingyue.pdf;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Foreground lifetime for a user-started offline export; durable records handle process death. */
public final class EpubExportService extends Service {
    private static final String CHANNEL="epub_export";private static final int NOTICE=1364;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){@Override public void run(){EpubConversionCoordinator.Session active=null;
        for(EpubConversionCoordinator.Session s:EpubConversionCoordinator.sessions())
            if(s.snapshot().state==EpubConversionCoordinator.State.RUNNING){active=s;break;}
        if(active==null){stopSelf();return;}
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(active));}
        catch(SecurityException denied){/* POST_NOTIFICATIONS denied: in-app export center remains available. */}
        handler.postDelayed(this,2500);}};
    @Override public void onCreate(){super.onCreate();EpubConversionCoordinator.initialize(this);
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"EPUB 导出",
                NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(channel);}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){EpubConversionCoordinator.Session active=null;
        for(EpubConversionCoordinator.Session s:EpubConversionCoordinator.sessions())
            if(s.snapshot().state==EpubConversionCoordinator.State.RUNNING){active=s;break;}
        if(active==null){stopSelf(startId);return START_NOT_STICKY;}
        Notification notice=notification(active);
        try{if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTICE,notice);}
        catch(RuntimeException denied){active.interrupt();stopSelf(startId);return START_NOT_STICKY;}
        handler.removeCallbacks(poll);handler.post(poll);return START_NOT_STICKY;}
    private Notification notification(EpubConversionCoordinator.Session task){
        EpubConversionCoordinator.Snapshot s=task.snapshot();Intent target=new Intent(this,ExportCenterActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,0,target,PendingIntent.FLAG_UPDATE_CURRENT|
                (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("正在导出："+task.title())
                .setContentText(s.stage+" · "+s.completed+"/"+s.total+" 页").setContentIntent(open)
                .setOngoing(true).setProgress(Math.max(1,s.total),s.completed,false).build();
    }
    @Override public void onTimeout(int startId,int fgsType){for(EpubConversionCoordinator.Session s:EpubConversionCoordinator.sessions())
        if(s.snapshot().state==EpubConversionCoordinator.State.RUNNING)
            s.interrupt("系统后台运行时间已到；可从导出中心继续");stopSelf(startId);}
    @Override public void onDestroy(){handler.removeCallbacks(poll);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}

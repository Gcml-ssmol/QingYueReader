package cn.qingyue.pdf;
import android.content.*;import android.graphics.*;import android.widget.*;import java.io.*;
final class AppColors {static final int CANVAS=0xffeeeeee,PAPER=0xffffffff,SURFACE=-1,INK=0xff111111,BORDER=0xffdddddd,MUTED=0xff777777,ACCENT=0xff008800,ACCENT_DARK=0xff006600,SOFT=0xffddddcc;}
final class ZoomBadge {void draw(Canvas c,int w,float d,double z){}}
final class NoteStore {static int saves;static boolean imageExists(Context c,String id,String name){return true;}static void save(Context c,String id,NoteDocument d)throws IOException{saves++;}interface Failure {void call(String m);}static void saveAsync(Context c,String id,NoteDocument d,Failure f){saves++;}static File imagesDirectory(Context c,String id)throws IOException{return new File(".");}}
final class NoteTextLayout {static double height(String s,float size,int ink,double width){return size*2;}static void draw(Canvas c,NoteDocument.Item i){Paint p=new Paint(1);p.setColor(i.color);c.drawText(i.text,(float)i.left(),(float)i.top(),p);}}
final class ReaderSourceLink {String toUri(){return "qingyue://source?test";}}
final class NoteSourceActions {static String body(ReaderSourceLink s){return "source";}}
final class NoteDialogs {static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);return l;}static TextView label(Context c,String text,int size,int ink){TextView t=new TextView(c);t.setText(text);t.setTextSize(size);return t;}static Object shape(Context c,int color,int radius){return null;}static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}}


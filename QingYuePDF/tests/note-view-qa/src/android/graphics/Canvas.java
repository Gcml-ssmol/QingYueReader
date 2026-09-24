package android.graphics;
import java.util.*;
/** Records transformed drawing commands; deliberately does not emulate Android rasterization. */
public class Canvas {
 public static class Command {public final String kind;public final int color;public final double x,y;public final String text;Command(String k,int c,double x,double y,String t){kind=k;color=c;this.x=x;this.y=y;text=t;}}
 public final List<Command> commands=new ArrayList<>();private double tx,ty,sx=1,sy=1;private final List<double[]> stack=new ArrayList<>();
 public int save(){stack.add(new double[]{tx,ty,sx,sy});return stack.size();}
 public void restoreToCount(int n){double[] v=stack.get(n-1);tx=v[0];ty=v[1];sx=v[2];sy=v[3];while(stack.size()>=n)stack.remove(stack.size()-1);}
 public void translate(float x,float y){tx+=x*sx;ty+=y*sy;}public void scale(float x,float y){sx*=x;sy*=y;}
 private void add(String k,float x,float y,Paint p,String t){commands.add(new Command(k,p.getColor(),tx+x*sx,ty+y*sy,t));}
 public void drawColor(int color){}
 public void drawRect(float l,float t,float r,float b,Paint p){add("rect",l,t,p,"");}public void drawRect(RectF r,Paint p){drawRect(r.left,r.top,r.right,r.bottom,p);}
 public void clipRect(float l,float t,float r,float b){}
 public void drawLine(float x,float y,float xx,float yy,Paint p){add("line",x,y,p,"");}
 public void drawCircle(float x,float y,float r,Paint p){add("circle",x,y,p,"");}
 public void drawOval(float l,float t,float r,float b,Paint p){add("oval",l,t,p,"");}
 public void drawPath(Path path,Paint p){if(!path.points.isEmpty())add("path",path.points.get(0)[0],path.points.get(0)[1],p,"");}
 public void drawBitmap(Bitmap b,Rect src,RectF dest,Paint p){add("bitmap",dest.left,dest.top,p,"");}
 public void drawText(String s,float x,float y,Paint p){add("text",x,y,p,s);}
}

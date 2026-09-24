package android.view;
import android.content.Context;import android.content.res.Resources;import android.graphics.*;import java.util.*;
public class View {
 public static final int LAYER_TYPE_HARDWARE=2; protected final Context context;private int width,height,measuredWidth,measuredHeight;private CharSequence description;private OnClickListener listener;
 public Rect visible=new Rect(0,0,800,600);public int screenX,screenY;public int padding;public Object layoutParams;
 public View(Context c){context=c;}public Context getContext(){return context;}public Resources getResources(){return context.getResources();}public int getWidth(){return width;}public int getHeight(){return height;}
 public void layout(int l,int t,int r,int b){int ow=width,oh=height;width=r-l;height=b-t;onSizeChanged(width,height,ow,oh);}protected void onSizeChanged(int w,int h,int ow,int oh){}protected void onDraw(Canvas c){}public void draw(Canvas c){onDraw(c);}
 public boolean onTouchEvent(MotionEvent e){return false;}public boolean onHoverEvent(MotionEvent e){return false;}public boolean performClick(){if(listener!=null)listener.onClick(this);return true;}
 public ViewParent getParent(){return null;}public void setFocusable(boolean v){}public void setClickable(boolean v){}public void setEnabled(boolean v){}public void setAlpha(float v){}public void setContentDescription(CharSequence s){description=s;}public CharSequence getContentDescription(){return description;}public void setLayerType(int type,Object p){}public void invalidate(){}public boolean post(Runnable r){r.run();return true;}
 public void setPadding(int l,int t,int r,int b){padding=t+b;}public void setBackground(Object d){}public void setBackgroundColor(int c){}
 public interface OnClickListener{void onClick(View v);}public void setOnClickListener(OnClickListener l){listener=l;}
 public void getWindowVisibleDisplayFrame(Rect r){r.set(visible.left,visible.top,visible.right,visible.bottom);}public void getLocationOnScreen(int[] p){p[0]=screenX;p[1]=screenY;}
 public void measure(int w,int h){measuredWidth=MeasureSpec.getSize(w);measuredHeight=padding+24;}protected void setMeasuredDimension(int w,int h){measuredWidth=w;measuredHeight=h;}public int getMeasuredHeight(){return measuredHeight;}public int getMeasuredWidth(){return measuredWidth;}
 public static class MeasureSpec {public static final int AT_MOST=0x80000000,UNSPECIFIED=0;public static int makeMeasureSpec(int size,int mode){return size|mode;}public static int getSize(int spec){return spec&0x3fffffff;}}
}

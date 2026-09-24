package android.view;public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
 public static final int TOOL_TYPE_FINGER=1,TOOL_TYPE_STYLUS=2,TOOL_TYPE_ERASER=4;
 private final int action,index;private final long time;private final int[] ids,tools;private final float[] xs,ys;public float major=1,size=.01f;
 public MotionEvent(int action,int index,long time,int[] ids,int[] tools,float[] xs,float[] ys){this.action=action;this.index=index;this.time=time;this.ids=ids;this.tools=tools;this.xs=xs;this.ys=ys;}
 public int getActionMasked(){return action;}public int getActionIndex(){return index;}public long getEventTime(){return time;}public int getPointerCount(){return ids.length;}public int getPointerId(int i){return ids[i];}public int getToolType(int i){return tools[i];}public float getX(){return xs[0];}public float getY(){return ys[0];}public float getX(int i){return xs[i];}public float getY(int i){return ys[i];}public float getTouchMajor(int i){return major;}public float getSize(int i){return size;}public int findPointerIndex(int id){for(int i=0;i<ids.length;i++)if(ids[i]==id)return i;return -1;}public int getHistorySize(){return 0;}public float getHistoricalX(int i,int h){return xs[i];}public float getHistoricalY(int i,int h){return ys[i];}
}

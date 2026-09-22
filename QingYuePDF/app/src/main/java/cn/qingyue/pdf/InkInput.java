package cn.qingyue.pdf;

/** MotionEvent tool types: finger=1, stylus=2, mouse=3, eraser=4. */
public final class InkInput {
    private InkInput(){}
    public static boolean isPen(int toolType){return toolType==2||toolType==4;}
    public static boolean canTouchDraw(boolean penOnly){return !penOnly;}
    public static final int TOUCH=0,BEGIN=1,MOVE=2,END=3,CANCEL=4,IGNORE=5;
    /** Color picking permits a finger, but a real pen takes priority over a resting palm. */
    public static final class Picker {
        public int activeId=-1;
        private boolean pen;
        public void reset(){activeId=-1;pen=false;}
        public int route(int action,int changedId,int changedTool,boolean activePresent){
            if(action==0){activeId=changedId;pen=isPen(changedTool);return BEGIN;}
            if(action==3){reset();return CANCEL;}
            if(action==5&&isPen(changedTool)&&!pen){activeId=changedId;pen=true;return BEGIN;}
            if(activeId==-1)return IGNORE;
            if(!activePresent){reset();return CANCEL;}
            if(action==2)return MOVE;
            if((action==1||action==6)&&changedId==activeId){reset();return END;}
            return IGNORE;
        }
    }
    /** The action values match MotionEvent, but this router can be tested on the JVM. */
    public static final class Router {
        public int activeId=-1;
        public void reset(){activeId=-1;}
        public int route(int action,int changedId,int changedTool,boolean editing,boolean activePresent){
            if(activeId!=-1){
                if(action==3||!activePresent){reset();return CANCEL;}
                if(action==2)return MOVE;
                if((action==1||action==6)&&changedId==activeId){reset();return END;}
                return IGNORE;
            }
            if((action==0||action==5)&&isPen(changedTool)&&editing){activeId=changedId;return BEGIN;}
            return TOUCH;
        }
    }
}

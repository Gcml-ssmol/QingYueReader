package cn.qingyue.pdf;

public final class ColorPickInputTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        InkInput.Picker p=new InkInput.Picker();
        check(p.route(0,10,1,false)==InkInput.BEGIN&&p.activeId==10,"Finger pick must begin");
        check(p.route(5,20,1,true)==InkInput.IGNORE&&p.activeId==10,"Second finger stole pick");
        check(p.route(5,30,2,true)==InkInput.BEGIN&&p.activeId==30,"Pen must replace earlier palm");
        check(p.route(2,10,1,true)==InkInput.MOVE&&p.activeId==30,"Movement must retain pen ID");
        check(p.route(6,10,1,true)==InkInput.IGNORE,"Palm lift committed a color");
        check(p.route(6,30,2,true)==InkInput.END&&p.activeId==-1,"Pen lift must commit");
        p.route(0,1,2,false);check(p.route(5,2,1,true)==InkInput.IGNORE&&p.activeId==1,"Palm stole an active pen");
        check(p.route(3,1,2,true)==InkInput.CANCEL&&p.activeId==-1,"Cancel must not commit");
        p.route(0,3,1,false);check(p.route(1,3,1,true)==InkInput.END,"Ordinary finger tap failed");
        p.route(0,4,1,false);check(p.route(2,5,1,false)==InkInput.CANCEL,"Lost pointer must cancel");
        p.route(0,6,4,false);check(p.activeId==6,"Pen eraser must be accepted for picking");
        p.reset();check(p.route(2,6,4,false)==InkInput.IGNORE,"Move after reset must not sample");
        System.out.println("PASS: finger sampling, pen priority, palm rejection, pointer identity, up/cancel and missing-pointer handling");
    }
}

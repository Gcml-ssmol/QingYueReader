package cn.qingyue.pdf;

public final class HorizontalPagingTest {
    private static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    private static void near(float a,float b,String s){check(Math.abs(a-b)<0.001f,s+": "+a+" != "+b);}
    public static void main(String[] args){
        near(HorizontalPaging.fitWidth(1000,700,1.5,10),680/1.5f,"portrait PDF fits tablet height");
        near(HorizontalPaging.fitWidth(400,850,1.5,10),380,"portrait phone fits width");
        near(HorizontalPaging.fitWidth(1000,700,0.5,10),980,"landscape page fits width");
        near(HorizontalPaging.fitWidth(1,1,10,10),1,"tiny viewport stays positive");
        check(HorizontalPaging.target(3,8,-200,0,64,0,0)==4,"advance");
        check(HorizontalPaging.target(3,8,200,0,64,0,0)==2,"previous");
        check(HorizontalPaging.target(0,8,200,0,64,0,0)==0,"first-page boundary");
        check(HorizontalPaging.target(7,8,-200,0,64,0,0)==7,"last-page boundary");
        check(HorizontalPaging.target(0,1,-200,0,64,0,0)==0,"one-page document");
        check(HorizontalPaging.target(3,8,0,0,0,0,0)==3,"zero travel");
        check(HorizontalPaging.target(3,8,60,0,64,0,0)==3,"short gesture returns");
        check(HorizontalPaging.target(3,8,200,170,64,0,0)==3,"diagonal does not flip");
        check(HorizontalPaging.target(3,8,0,200,64,0,0)==3,"vertical does not flip");
        check(HorizontalPaging.target(3,8,-200,0,64,0,250)==3,"zoomed middle only pans");
        check(HorizontalPaging.target(3,8,-200,0,64,-250,250)==4,"zoomed edge advances");
        check(HorizontalPaging.target(3,8,200,0,64,250,250)==2,"opposite zoomed edge returns");
        near(HorizontalPaging.clampPan(500,400,800,10),0,"small content stays centered");
        near(HorizontalPaging.clampPan(500,1000,800,10),110,"positive page edge");
        near(HorizontalPaging.clampPan(-500,1000,800,10),-110,"negative page edge");
        near(HorizontalPaging.clampPan(75,1000,800,10),75,"in-page pan preserved");
        System.out.println("PASS: page fit across orientations, first/last boundaries, short/diagonal/vertical rejection, zoomed pan and edge turns");
    }
}

package cn.qingyue.pdf;

import java.io.IOException;

public final class DocumentPagePlanTest {
    public static void main(String[] args) throws Exception {
        DocumentPagePlan phone=DocumentPagePlan.create(true,1);
        check(phone.width==360&&phone.height==640,"phone dimensions");
        check(phone.left==22&&phone.top==55&&phone.bottom==594,"phone margins");
        check(phone.textSize==16.5f&&phone.lineExtra==4f,"phone medium type");
        for(int i=0;i<3;i++)check(DocumentPagePlan.create(true,i).textSize
                <DocumentPagePlan.create(true,i+1<3?i+1:i).textSize||i==2,"font order");
        DocumentPagePlan legacy=DocumentPagePlan.create(false,0);
        check(legacy.width==595&&legacy.height==842&&legacy.textSize==12.5f,
                "legacy A4 metrics");
        check(DocumentPagePlan.create(false,2).textSize==17f,"large A4 type");
        DocumentPagePlan.requirePage(400);
        try {DocumentPagePlan.requirePage(401);throw new AssertionError("page cap missing");}
        catch(IOException expected){check(expected.getMessage().contains("400"),"page cap message");}
        System.out.println("DocumentPagePlanTest OK");
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}

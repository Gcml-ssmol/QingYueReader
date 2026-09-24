package android.content;
import android.content.res.Resources;
public class Context {
    public final ContentResolver resolver=new ContentResolver();
    public final Resources resources=new Resources();
    public ContentResolver getContentResolver(){return resolver;}
    public Resources getResources(){return resources;}
    public String getPackageName(){return "cn.qingyue.pdf";}
}

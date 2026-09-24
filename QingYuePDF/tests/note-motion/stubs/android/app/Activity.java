package android.app;
public class Activity extends android.content.Context {
    public int first,second,calls;
    public void overridePendingTransition(int first,int second){this.first=first;this.second=second;calls++;}
}

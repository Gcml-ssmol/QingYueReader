package android.content.res;
public class Resources {
    public String lastName;
    public int getIdentifier(String name,String type,String pkg){lastName=name;return name.hashCode();}
}

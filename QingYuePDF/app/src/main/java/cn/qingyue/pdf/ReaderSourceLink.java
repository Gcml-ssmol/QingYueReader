package cn.qingyue.pdf;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;

/** Stable page-level source link exchanged with LearningActivity or note objects. */
public final class ReaderSourceLink {
    public static final String SCHEME="qingyue", HOST="source";private static final int MAX_URI_LENGTH=65536;
    public final String documentId,documentName,quote;public final int page;public final float x,y;
    public ReaderSourceLink(String documentId,String documentName,int page,float x,float y,String quote){
        if(documentId==null||documentId.length()==0||documentId.length()>160||page<0||!finite(x)||!finite(y)||x<0||x>1||y<0)throw new IllegalArgumentException("Invalid source link");
        this.documentId=documentId;this.documentName=limit(documentName,240);this.page=page;this.x=x;this.y=y;this.quote=limit(quote,4096);
    }
    public static ReaderSourceLink page(String id,String name,int page){return new ReaderSourceLink(id,name,page,0,0,"");}
    public static ReaderSourceLink selection(String id,String name,int page,String text,List<ReaderDocument.Box> boxes){
        ReaderDocument.Box box=boxes==null||boxes.isEmpty()?null:boxes.get(0);
        return new ReaderSourceLink(id,name,page,box==null?0:box.left,box==null?0:box.top,text);
    }
    public String toUri(){return SCHEME+"://"+HOST+"?v=1&doc="+encode(documentId)+"&name="+encode(documentName)+"&page="+page+"&x="+Float.toString(x)+"&y="+Float.toString(y)+"&quote="+encode(quote);}
    public static ReaderSourceLink parse(String uri){
        if(uri==null||uri.length()>MAX_URI_LENGTH||!uri.startsWith(SCHEME+"://"+HOST+"?"))throw new IllegalArgumentException("Unsupported source link");
        try{Map<String,String> values=new HashMap<>();String query=uri.substring(uri.indexOf('?')+1);for(String pair:query.split("&")){int split=pair.indexOf('=');if(split>0){String key=decode(pair.substring(0,split));if(values.containsKey(key))throw new IllegalArgumentException("Duplicate source field");values.put(key,decode(pair.substring(split+1)));}}
            String version=values.get("v");if(version!=null&&!version.equals("1"))throw new IllegalArgumentException("Unknown source version");return new ReaderSourceLink(values.get("doc"),values.get("name"),Integer.parseInt(values.get("page")),Float.parseFloat(values.get("x")),Float.parseFloat(values.get("y")),values.get("quote"));}
        catch(RuntimeException problem){throw new IllegalArgumentException("Malformed source link",problem);}
    }
    private static String encode(String value){try{return URLEncoder.encode(value==null?"":value,"UTF-8");}catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    private static String decode(String value){try{return URLDecoder.decode(value,"UTF-8");}catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    private static String limit(String value,int length){if(value==null)return "";if(value.length()<=length)return value;int end=length;if(end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);}
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
}

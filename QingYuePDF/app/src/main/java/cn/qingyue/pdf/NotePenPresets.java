package cn.qingyue.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small local pen-box preferences, independent of note content and its file version. */
final class NotePenPresets {
    static final int LIMIT=8;
    static final class Preset {
        final String tool; final int color; final float width;
        Preset(String tool,int color,float width){this.tool=tool;this.color=color|0xff000000;this.width=width;}
        String title(){return name(tool)+" · "+String.format(Locale.ROOT,"#%06X · %.1f pt",color&0xffffff,width);}
    }
    static boolean supported(String tool){return "pen".equals(tool)||"ballpoint".equals(tool)||"pencil".equals(tool)||"marker".equals(tool);}
    static String name(String tool){return "marker".equals(tool)?"荧光笔":"pencil".equals(tool)?"铅笔":"ballpoint".equals(tool)?"圆珠笔":"钢笔";}
    static boolean valid(Preset p){return p!=null&&supported(p.tool)&&!Float.isNaN(p.width)&&!Float.isInfinite(p.width)&&p.width>=("marker".equals(p.tool)?4:.5)&&p.width<=("marker".equals(p.tool)?60:20);}
    static ArrayList<Preset> read(String text){
        ArrayList<Preset> list=new ArrayList<>();
        if(text==null){list.add(new Preset("pen",0xff36302b,2));list.add(new Preset("ballpoint",0xff2464d6,1.3f));list.add(new Preset("marker",0xfff6c343,18));return list;}
        if(text.length()>4096)return list;
        for(String row:text.split(";")){
            if(list.size()==LIMIT)break;
            String[] fields=row.split(",");if(fields.length!=3)continue;
            try{Preset p=new Preset(fields[0],(int)Long.parseLong(fields[1],16),Float.parseFloat(fields[2]));if(valid(p)&&!contains(list,p))list.add(p);}catch(NumberFormatException ignored){}
        }
        return list;
    }
    static boolean contains(List<Preset> list,Preset candidate){for(Preset p:list)if(p.tool.equals(candidate.tool)&&p.color==candidate.color&&Math.abs(p.width-candidate.width)<.01)return true;return false;}
    static String write(List<Preset> list){
        StringBuilder result=new StringBuilder();int count=0;
        for(Preset p:list){if(!valid(p))continue;if(count++==LIMIT)break;if(result.length()>0)result.append(';');result.append(p.tool).append(',').append(Integer.toHexString(p.color)).append(',').append(Float.toString(p.width));}
        return result.toString();
    }
}


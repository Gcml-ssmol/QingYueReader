package cn.qingyue.pdf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable identity for a new reflow layout; separate from the raw-source hash namespace. */
final class ImportVariantKey {
    private ImportVariantKey() { }
    static String hash(String sourceHash,String page,String font){
        if(sourceHash==null||!sourceHash.matches("[0-9a-fA-F]{64}")||page==null||font==null)
            throw new IllegalArgumentException("import identity");
        String key="QY-REFLOW-LAYOUT-v1\n"+sourceHash.toLowerCase(java.util.Locale.ROOT)
                +"\n"+page+"\n"+font;
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            char[] hex=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();
            for(int i=0;i<bytes.length;i++){hex[i*2]=digits[(bytes[i]>>>4)&15];hex[i*2+1]=digits[bytes[i]&15];}
            return new String(hex);
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
}

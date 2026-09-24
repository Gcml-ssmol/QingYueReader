import cn.qingyue.pdf.PdfTextImport;
import java.io.*;
import java.nio.charset.*;
import java.util.Arrays;

public final class PdfTextImportTest {
    public static void main(String[] args) throws Exception {
        String text="中文 English\n第二行\t备注";
        check(PdfTextImport.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).equals(text),"UTF8");
        check(PdfTextImport.read(new ByteArrayInputStream(text.getBytes(Charset.forName("GB18030")))).equals(text),"GB18030");
        byte[] body=text.getBytes(StandardCharsets.UTF_16LE);byte[] bom=new byte[body.length+2];bom[0]=(byte)255;bom[1]=(byte)254;System.arraycopy(body,0,bom,2,body.length);
        check(PdfTextImport.read(new ByteArrayInputStream(bom)).equals(text),"UTF16 BOM");
        check(PdfTextImport.read(new ByteArrayInputStream("a\r\nb\rc".getBytes(StandardCharsets.UTF_8))).equals("a\nb\nc"),"line endings");
        rejects(new byte[]{0,1,2});rejects("  \r\n".getBytes(StandardCharsets.UTF_8));
        byte[] large=new byte[65537];Arrays.fill(large,(byte)'a');rejects(large);
        rejects(new String(new char[8193]).replace('\0','x').getBytes(StandardCharsets.UTF_8));
        rejects(new byte[]{(byte)239,(byte)187,(byte)191,(byte)255});
        System.out.println("PdfTextImportTest passed: UTF8, UTF16, GB18030, multiline, invalid/binary/empty and size limits");
    }
    static void rejects(byte[] value)throws Exception{try{PdfTextImport.read(new ByteArrayInputStream(value));throw new AssertionError("invalid input accepted");}catch(IOException expected){}}
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}

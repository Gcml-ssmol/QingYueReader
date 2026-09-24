package cn.qingyue.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded TXT reader for a single editable PDF text box. */
public final class PdfTextImport {
    private PdfTextImport() { }
    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法读取 TXT 文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
            if (bytes.size() + count > 65536) throw new IOException("TXT 文件超过 64 KB，请分段添加");
            bytes.write(buffer, 0, count);
        }
        byte[] data = bytes.toByteArray(); String text;
        if (data.length >= 3 && (data[0]&255)==239 && (data[1]&255)==187 && (data[2]&255)==191)
            text = decode(data, 3, StandardCharsets.UTF_8);
        else if (data.length >= 2 && (data[0]&255)==255 && (data[1]&255)==254)
            text = decode(data, 2, StandardCharsets.UTF_16LE);
        else if (data.length >= 2 && (data[0]&255)==254 && (data[1]&255)==255)
            text = decode(data, 2, StandardCharsets.UTF_16BE);
        else {
            try { text = decode(data, 0, StandardCharsets.UTF_8); }
            catch (CharacterCodingException notUtf8) { text = decode(data, 0, Charset.forName("GB18030")); }
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.trim().isEmpty()) throw new IOException("TXT 文件没有可添加的文字");
        if (text.length() > 8192) throw new IOException("每个文字框最多 8192 个字符，请分段添加");
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if ((c<32 && c!='\n' && c!='\t') || c==127) throw new IOException("文件含非文本数据，请选择 TXT 文件");
        }
        return text;
    }
    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length-offset)).toString();
    }
}

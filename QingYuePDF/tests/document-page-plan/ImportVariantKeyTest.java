package cn.qingyue.pdf;

public final class ImportVariantKeyTest {
    public static void main(String[] args) {
        String source="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String same=ImportVariantKey.hash(source,"PHONE","MEDIUM");
        check(same.equals(ImportVariantKey.hash(source.toUpperCase(java.util.Locale.ROOT),
                "PHONE","MEDIUM")),"same source and layout must be stable");
        check(!same.equals(source),"new layout identity must differ from legacy source hash");
        check(!same.equals(ImportVariantKey.hash(source,"A4","MEDIUM")),"page variant");
        check(!same.equals(ImportVariantKey.hash(source,"PHONE","LARGE")),"font variant");
        check(!same.equals(ImportVariantKey.hash("b"+source.substring(1),"PHONE","MEDIUM")),
                "source variant");
        check(same.matches("[0-9a-f]{64}"),"SHA-256 format");
        System.out.println("ImportVariantKeyTest OK");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

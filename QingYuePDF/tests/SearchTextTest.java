package cn.qingyue.pdf;

public final class SearchTextTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void found(String text, String query, int count) {
        SearchText.Match match = SearchText.find(text, query);
        check(match != null && match.count == count, "Missing/count mismatch: " + query);
    }
    public static void main(String[] args) {
        found("Hello PDF\nhello pdf", "HELLO PDF", 2);
        found("随机 变量\n的分布，随机变量的分布", "随机变量的分布", 2);
        found("full\n   text\tsearch", "full text search", 1);
        found("ＡＢＣ１２３ 与 ﬁle", "abc123", 1);
        found("ＡＢＣ１２３ 与 ﬁle", "file", 1);
        found("概\u200b率\u00ad论\ufeff", "概率论", 1);
        found("page\u00a0 two", "page two", 1);
        found("banana", "ana", 1);
        check(SearchText.find("test", "   ") == null, "Empty query");
        check(SearchText.find(null, "text") == null, "Null text");
        check(SearchText.find("", "text") == null, "Scan has no match");
        check(SearchText.find("other", "text") == null, "False positive");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append("前");
        longText.append("PDF SEARCH");
        for (int i = 0; i < 200; i++) longText.append("后");
        SearchText.Match snippet = SearchText.find(longText.toString(), "pdf search");
        check(snippet.snippet.contains("PDF SEARCH"), "Snippet preserves case and match");
        check(snippet.snippet.startsWith("…") && snippet.snippet.endsWith("…"), "Snippet context ellipses");
        check(snippet.snippet.length() <= 140, "Snippet bound");
        found("İİİ keyword", "KEYWORD", 1);
        check(SearchText.find("İİİ keyword", "KEYWORD").snippet.contains("keyword"), "Expanded case offsets");
        found("\ud840\udc00 \ud840\udc01", "\ud840\udc00\ud840\udc01", 1);
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 2000; i++) longQuery.append('a');
        check(SearchText.find(longQuery.toString(), longQuery.toString()).snippet.length() <= 203,
                "A pasted long query must not retain large page text in every result");
        System.out.println("SearchTextTest passed");
    }
}

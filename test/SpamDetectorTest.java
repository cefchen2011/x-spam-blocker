package com.dsh.xspamblock;

/** Host-side test for SpamDetector (it only touches java.util.regex, so it runs on the JVM). */
public class SpamDetectorTest {

    private static int failed = 0;

    public static void main(String[] args) {
        expect("@xiaomei520,\u54e5\u54e5\u7ea6\u5417\uff0c\u59b9\u59b9\u4e0a\u95e8\u670d\u52a1\u54e6", "xiaomei520");
        expect("@abc123,\u5168\u5957\u670d\u52a1\uff0c\u52a0\u5fae\u4fe1", "abc123");
        expect("@nina_88\uff0c\u9a9a\u8d27\u5728\u7ebf\u7b49\u4f60\uff0c\u79c1\u804a", "nina_88");
        expect("@sweet,\u60f3\u88ab\u54e5\u54e5\u75bc\uff0c\u79c1\u804a\u6211", "sweet");
        expect("@hottie, nude pics available, dm me", "hottie");
        expect("@user123\uff0c\u7ea6\u70ae\u8bf7\u79c1\u4fe1", "user123");

        reject("@user,\u4eca\u5929\u5929\u6c14\u4e0d\u9519");
        reject("@a,\u5403\u996d\u4e86\u5417");
        reject("\u666e\u901a\u5e16\u5b50\uff0c\u6ca1\u6709\u63d0\u53ca\u7b26");
        reject("@friend\uff0c\u8c22\u8c22\u4f60\u7684\u5e2e\u52a9");
        reject("");
        reject(null);

        if (failed == 0) {
            System.out.println("ALL PASS");
        } else {
            System.out.println(failed + " FAILED");
            System.exit(1);
        }
    }

    private static void expect(String text, String handle) {
        String got = SpamDetector.detect(text);
        if (handle.equals(got)) {
            System.out.println("ok   -> @" + got + "   " + shorten(text));
        } else {
            failed++;
            System.out.println("FAIL expected @" + handle + " but got " + got + "   " + shorten(text));
        }
    }

    private static void reject(String text) {
        String got = SpamDetector.detect(text);
        if (got == null) {
            System.out.println("ok   -> null   " + shorten(text));
        } else {
            failed++;
            System.out.println("FAIL expected null but got @" + got + "   " + shorten(text));
        }
    }

    private static String shorten(String s) {
        if (s == null) return "<null>";
        return s.length() <= 44 ? s : s.substring(0, 44) + "...";
    }
}

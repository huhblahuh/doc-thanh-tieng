package app.docthanhtieng;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cuts text into short sentences so the speech engine never gets a long block. */
final class Splitter {
    static final class Seg {
        final String t;
        final int p;

        Seg(String t, int p) {
            this.t = t;
            this.p = p;
        }
    }

    private static final int MAX = 260;
    private static final Pattern SENT =
            Pattern.compile("(.+?)([.!?…]+[\"'”’)\\]]*(?=\\s|$)|$)\\s*");
    private static final Pattern INITIAL = Pattern.compile("(^|[\\s.(\"\u201C])\\p{Lu}\\.\\s*$");
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    /** Drop markdown marks (# * > etc.) before reading. */
    static volatile boolean clean = true;

    private Splitter() {}

    static String cleanLine(String s) {
        s = s.replaceAll("^#{1,6}\\s+", "");
        s = s.replaceAll("^>+\\s*", "");
        s = s.replaceAll("^[-*+]\\s+", "");
        s = s.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\*+", "");
        s = s.replaceAll("`+", "");
        s = s.replaceAll("~~", "");
        s = s.replaceAll("(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])", "");
        if (s.matches("[\\s\\-=_|:]*")) return "";
        return s.trim();
    }

    /** "vi" when the text carries Vietnamese letters, otherwise "en". */
    static String detect(String text) {
        if (text == null) return "vi";
        int n = Math.min(text.length(), 4000), letters = 0, vi = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            char l = Character.toLowerCase(c);
            if (l == '\u0111' || l == '\u0103' || l == '\u01A1' || l == '\u01B0' || (c >= 0x1EA0 && c <= 0x1EF9)) vi++;
        }
        if (letters == 0) return "vi";
        return vi * 100 >= letters * 2 ? "vi" : "en";
    }

    static List<Seg> split(String text) {
        List<Seg> out = new ArrayList<>();
        if (text == null) return out;
        String[] paras = text.replace("\r", "").split("\n+");
        int p = 0;
        for (String raw : paras) {
            String para = raw.trim();
            if (clean) para = cleanLine(para);
            if (para.isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            Matcher m = SENT.matcher(para);
            while (m.find()) {
                if (m.group().isEmpty()) break;
                parts.add(m.group());
            }
            if (parts.isEmpty()) parts.add(para);
            // "Q.P. co kha nang": a lone capital before the dot is an initial, not a sentence end.
            for (int i = 0; i + 1 < parts.size(); ) {
                if (INITIAL.matcher(parts.get(i)).find()) {
                    parts.set(i, parts.get(i) + parts.remove(i + 1));
                } else {
                    i++;
                }
            }
            boolean any = false;
            for (String part : parts) {
                for (String piece : shorten(part)) {
                    if (HAS_WORD.matcher(piece).find()) {
                        out.add(new Seg(piece, p));
                        any = true;
                    } else if (any) {
                        Seg last = out.remove(out.size() - 1);
                        out.add(new Seg(last.t + piece, last.p));
                    }
                }
            }
            if (any) p++;
        }
        return out;
    }

    private static List<String> shorten(String s) {
        List<String> res = new ArrayList<>();
        String rest = s;
        while (rest.length() > MAX) {
            String win = rest.substring(0, MAX);
            int cut = Math.max(win.lastIndexOf(", "), Math.max(win.lastIndexOf(": "), win.lastIndexOf("; ")));
            if (cut > 60) {
                cut += 2;
            } else {
                int sp = win.lastIndexOf(' ');
                cut = sp > 60 ? sp + 1 : MAX;
            }
            res.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        if (!rest.isEmpty()) res.add(rest);
        return res;
    }
}

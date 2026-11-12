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
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private Splitter() {}

    static List<Seg> split(String text) {
        List<Seg> out = new ArrayList<>();
        if (text == null) return out;
        String[] paras = text.replace("\r", "").split("\n+");
        int p = 0;
        for (String raw : paras) {
            String para = raw.trim();
            if (para.isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            Matcher m = SENT.matcher(para);
            while (m.find()) {
                if (m.group().isEmpty()) break;
                parts.add(m.group());
            }
            if (parts.isEmpty()) parts.add(para);
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

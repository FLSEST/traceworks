package traceworks.selfaudit.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version numbers: their order and how far apart two of them are. The order is close to Maven's for the versions that
 * count as releases: numbers compare as numbers, a missing number is 0, a number is newer than a word, and a version
 * without a qualifier is newer than one with ({@code 2.0 > 2.0-rc1}).
 */
final class Versions {

    private static final Pattern TOKEN = Pattern.compile("\\d+|[A-Za-z]+");

    /** The order of versions, oldest first. */
    static final Comparator<String> ORDER = Versions::compare;

    private Versions() {
    }

    static int compare(String a, String b) {
        List<String> x = tokens(a);
        List<String> y = tokens(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            String p = i < x.size() ? x.get(i) : null;
            String q = i < y.size() ? y.get(i) : null;
            int c = token(p, q);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int token(String p, String q) {
        boolean pn = p != null && Character.isDigit(p.charAt(0));
        boolean qn = q != null && Character.isDigit(q.charAt(0));
        if (p == null && q == null) return 0;
        if (p == null) return qn ? numeric("0", q) : 1;
        if (q == null) return pn ? numeric(p, "0") : -1;
        if (pn && qn) return numeric(p, q);
        if (pn) return 1;
        if (qn) return -1;
        return p.compareToIgnoreCase(q);
    }

    private static int numeric(String p, String q) {
        String a = p.replaceFirst("^0+(?=.)", "");
        String b = q.replaceFirst("^0+(?=.)", "");
        if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
        return a.compareTo(b);
    }

    private static List<String> tokens(String version) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(version);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** {@code major}, {@code minor} or {@code patch}: the first of the three numbers in which the versions differ. */
    static String jump(String from, String to) {
        long[] a = numbers(from);
        long[] b = numbers(to);
        if (a[0] != b[0]) return "major";
        if (a[1] != b[1]) return "minor";
        return "patch";
    }

    private static long[] numbers(String version) {
        long[] out = new long[3];
        Matcher m = Pattern.compile("\\d{1,18}").matcher(version);
        for (int i = 0; i < 3 && m.find(); i++) out[i] = Long.parseLong(m.group());
        return out;
    }

    /** The versions in {@code all} that count as releases and are newer than {@code current}, newest last. */
    static List<String> newer(String current, List<String> all, RuleFile rules) {
        List<String> out = new ArrayList<>();
        for (String v : all) {
            if (rules.stable(v) && compare(v, current) > 0 && !out.contains(v)) out.add(v);
        }
        out.sort(ORDER);
        return out;
    }
}

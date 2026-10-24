package com.platform.almbackend.realtime;

import java.util.regex.Pattern;

/** Redis Stream 엔트리 id({@code <ms>-<seq>}) 비교·검증 — 로컬 팬아웃도 같은 형식을 쓴다 */
final class StreamIds {
    static final String ZERO = "0-0";
    private static final Pattern FORMAT = Pattern.compile("\\d{1,19}-\\d{1,19}");

    private StreamIds() {}

    static boolean valid(String id) {
        if (id == null || !FORMAT.matcher(id).matches()) return false;
        try {
            parse(id);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** a < b 음수, 같으면 0, a > b 양수. 형식 검사는 호출자가 먼저 한다 */
    static int compare(String a, String b) {
        long[] x = parse(a);
        long[] y = parse(b);
        int c = Long.compare(x[0], y[0]);
        return c != 0 ? c : Long.compare(x[1], y[1]);
    }

    private static long[] parse(String id) {
        int dash = id.indexOf('-');
        return new long[]{Long.parseLong(id.substring(0, dash)), Long.parseLong(id.substring(dash + 1))};
    }
}

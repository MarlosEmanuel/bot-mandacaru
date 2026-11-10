package bot.ponto;

public final class TimeFormat {

    private TimeFormat() {
    }

    public static String duration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format("%dh %02dmin %02ds", h, m, s);
        }
        if (m > 0) {
            return String.format("%dmin %02ds", m, s);
        }
        return s + "s";
    }

    public static String compact(long ms) {
        long m = Math.max(0, ms) / 60_000;
        long h = m / 60;
        m %= 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }
}

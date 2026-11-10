package bot.ponto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Cálculo de semanas (domingo 00:00 → domingo 00:00 na zona configurada). */
public final class WeekUtil {

    private WeekUtil() {
    }

    /** Janela [startMs, endMs) de uma semana. */
    public record Window(long startMs, long endMs) {
    }

    /** Semana que contém {@code nowMs}, recuada {@code weeksAgo} semanas. */
    public static Window window(long nowMs, ZoneId zone, int weeksAgo) {
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        LocalDate sunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(weeksAgo);
        ZonedDateTime start = sunday.atStartOfDay(zone);
        ZonedDateTime end = sunday.plusWeeks(1).atStartOfDay(zone);
        return new Window(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
    }

    /** Próximo domingo 00:00 estritamente depois de {@code nowMs}. */
    public static long nextWeekStartMs(long nowMs, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        return today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }
}

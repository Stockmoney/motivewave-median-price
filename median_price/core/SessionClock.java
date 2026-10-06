package median_price.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;

/**
 * Turns a bar's timestamp into the trading day / week / month it belongs to, in exchange time. The platform
 * stores times as absolute instants, so the user's own time zone never matters and daylight saving is handled
 * by the zone rules.
 *
 * <p>Globex rule (CME): a trading day starts at 18:00 the evening before and ends at 17:00, so everything from
 * 18:00 counts to the NEXT calendar date. That puts Sunday 18:00 into Monday's week, and the evening of the
 * last day of a month into the next month.
 */
public final class SessionClock {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int GLOBEX_START_HOUR = 18;

    private final ZoneId zone;
    private final RthHours rth;

    public SessionClock(ZoneId zone, RthHours rth) {
        this.zone = zone;
        this.rth = rth;
    }

    public RthHours rth() { return rth; }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
    }

    // ---------------------------------------------------------------- Globex
    /** CME trade date: the calendar date, or the next one from 18:00 on. */
    public LocalDate tradeDate(long epochMillis) {
        LocalDateTime t = local(epochMillis);
        return t.getHour() >= GLOBEX_START_HOUR ? t.toLocalDate().plusDays(1) : t.toLocalDate();
    }

    // ---------------------------------------------------------------- RTH
    public boolean isRth(long epochMillis) {
        LocalDateTime t = local(epochMillis);
        DayOfWeek d = t.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && rth.contains(t.toLocalTime());
    }

    public LocalDate rthDate(long epochMillis) {
        return local(epochMillis).toLocalDate();
    }

    // ---------------------------------------------------------------- period keys
    /** Day key as the date's epoch day. */
    public static long dayKey(LocalDate date) { return date.toEpochDay(); }

    /** Week key: the epoch day of that date's Monday (Monday - Friday trading week). */
    public static long weekKey(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    public static long monthKey(LocalDate date) {
        YearMonth m = YearMonth.from(date);
        return m.getYear() * 12L + m.getMonthValue();
    }

    /** ISO week number, only used by tests to cross-check weekKey. */
    static int isoWeek(LocalDate date) { return date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR); }
}

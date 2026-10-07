package median_price.core;

import java.time.LocalDate;

/**
 * Streaming median price, (high + low) / 2, of the CURRENT day, week and month. Feed bars in time order; each
 * call returns the three medians as they were at that bar (they move whenever the period prints a new
 * extreme), so a plotted line is exactly what a trader would have seen at the time - nothing is looked ahead.
 *
 * <ul>
 *   <li>GLOBEX: ranges over every bar of the Globex trading day / week / month.</li>
 *   <li>RTH: ranges over regular-hours bars only; outside those hours the last value is held (frozen).</li>
 *   <li>MIX: the daily value follows RTH while regular hours are open and Globex otherwise; week and month
 *       are always Globex.</li>
 * </ul>
 * A value is {@link Double#NaN} until the first bar of that kind has been seen.
 */
public final class MedianCalc {

    /**
     * The three medians at one bar, plus the id of the line SEGMENT each belongs to. Bars that follow one
     * another with the same id are one segment; the "Straight line" display draws a segment flat at its
     * latest value. An id differs between Globex and RTH, so a Mix day splits into overnight / RTH / after close.
     */
    public record Mids(double day, double week, double month, long dayRun, long weekRun, long monthRun) {
        public double value(Period p) {
            return switch (p) { case DAY -> day; case WEEK -> week; case MONTH -> month; };
        }

        public long run(Period p) {
            return switch (p) { case DAY -> dayRun; case WEEK -> weekRun; case MONTH -> monthRun; };
        }
    }

    /** High / low of the period with the given key; a new key starts a new period. */
    private static final class Range {
        long key = Long.MIN_VALUE;
        double high = Double.NaN, low = Double.NaN;

        void add(long k, double h, double l) {
            if (k != key) { key = k; high = h; low = l; return; }
            if (h > high) high = h;
            if (l < low) low = l;
        }

        double mid() { return Double.isNaN(high) ? Double.NaN : (high + low) / 2; }

        /** Segment id: even for Globex ranges, odd for RTH ranges. */
        long run(boolean rth) { return key * 2 + (rth ? 1 : 0); }
    }

    private final SessionClock clock;
    private final Mode mode;

    private final Range gDay = new Range(), gWeek = new Range(), gMonth = new Range();
    private final Range rDay = new Range(), rWeek = new Range(), rMonth = new Range();

    /** Data may begin this long after a period opens (a missing first bar or two) and the period still counts as covered. */
    static final long SLACK_MS = 2 * 60 * 60 * 1000L;

    private final long dataStart;

    public MedianCalc(SessionClock clock, Mode mode) {
        this(clock, mode, Long.MIN_VALUE);
    }

    /**
     * @param dataStart start time of the first bar the calculation will be fed. A day, week or month that opened
     *                  BEFORE it is only partly known, so its median would be wrong; it is reported as NaN (no line)
     *                  instead of a plausible but false value. Long.MIN_VALUE = no check.
     */
    public MedianCalc(SessionClock clock, Mode mode, long dataStart) {
        this.clock = clock;
        this.mode = mode;
        this.dataStart = dataStart;
    }

    private boolean covered(long periodStart) {
        return dataStart == Long.MIN_VALUE || dataStart <= periodStart + SLACK_MS;
    }

    private double known(Range r, java.util.function.LongUnaryOperator start) {
        double mid = r.mid();
        if (Double.isNaN(mid)) return mid;                      // nothing seen yet: no key to look up either
        return covered(start.applyAsLong(r.key)) ? mid : Double.NaN;
    }

    public Mids feed(long epochMillis, double high, double low) {
        boolean rth = clock.isRth(epochMillis);

        LocalDate td = clock.tradeDate(epochMillis);
        gDay.add(SessionClock.dayKey(td), high, low);
        gWeek.add(SessionClock.weekKey(td), high, low);
        gMonth.add(SessionClock.monthKey(td), high, low);

        if (rth) {
            LocalDate rd = clock.rthDate(epochMillis);
            rDay.add(SessionClock.dayKey(rd), high, low);
            rWeek.add(SessionClock.weekKey(rd), high, low);
            rMonth.add(SessionClock.monthKey(rd), high, low);
        }

        double gd = known(gDay, k -> clock.dayStart(LocalDate.ofEpochDay(k)));
        double gw = known(gWeek, clock::weekStart), gm = known(gMonth, clock::monthStart);
        return switch (mode) {
            case GLOBEX -> new Mids(gd, gw, gm, gDay.run(false), gWeek.run(false), gMonth.run(false));
            case RTH -> new Mids(known(rDay, k -> clock.dayStart(LocalDate.ofEpochDay(k))), known(rWeek, clock::weekStart),
                    known(rMonth, clock::monthStart), rDay.run(true), rWeek.run(true), rMonth.run(true));
            case MIX -> new Mids(rth ? known(rDay, k -> clock.dayStart(LocalDate.ofEpochDay(k))) : gd, gw, gm,
                    rth ? rDay.run(true) : gDay.run(false), gWeek.run(false), gMonth.run(false));
        };
    }
}

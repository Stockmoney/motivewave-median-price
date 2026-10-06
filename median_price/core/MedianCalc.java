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

    public MedianCalc(SessionClock clock, Mode mode) {
        this.clock = clock;
        this.mode = mode;
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

        return switch (mode) {
            case GLOBEX -> new Mids(gDay.mid(), gWeek.mid(), gMonth.mid(),
                    gDay.run(false), gWeek.run(false), gMonth.run(false));
            case RTH -> new Mids(rDay.mid(), rWeek.mid(), rMonth.mid(),
                    rDay.run(true), rWeek.run(true), rMonth.run(true));
            case MIX -> new Mids(rth ? rDay.mid() : gDay.mid(), gWeek.mid(), gMonth.mid(),
                    rth ? rDay.run(true) : gDay.run(false), gWeek.run(false), gMonth.run(false));
        };
    }
}

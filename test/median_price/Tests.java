package median_price;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import median_price.core.ClosedBarSeries;
import median_price.core.Feed;
import median_price.core.History;
import median_price.core.MappedSeries;
import median_price.core.MedianCalc;
import median_price.core.Mode;
import median_price.core.Period;
import median_price.core.RthHours;
import median_price.core.SessionClock;
import median_price.core.StraightLines;

/** Plain-JVM tests (no MotiveWave, no framework); build.sh runs them on every build. */
public final class Tests {

    private static int passed, failed;

    public static void main(String[] args) {
        symbols();
        tradeDates();
        regularHours();
        globex();
        rthOnly();
        mix();
        history();
        mapped();
        closedOnly();
        runs();
        straight();
        System.out.println("tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers
    private static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); }
    }

    private static void eq(String what, Object expected, Object actual) {
        check(what + " (expected " + expected + ", got " + actual + ")", expected.equals(actual));
    }

    private static void near(String what, double expected, double actual) {
        boolean ok = Double.isNaN(expected) ? Double.isNaN(actual) : Math.abs(expected - actual) < 1e-9;
        check(what + " (expected " + expected + ", got " + actual + ")", ok);
    }

    private static long ny(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, SessionClock.NEW_YORK).toInstant().toEpochMilli();
    }

    private static final RthHours NQ_HOURS = RthHours.forSymbol("MNQZ6").orElseThrow();

    private static SessionClock clock() { return new SessionClock(SessionClock.NEW_YORK, NQ_HOURS); }

    // ------------------------------------------------------------------ symbols
    private static void symbols() {
        eq("MNQZ6 root", "MNQ", RthHours.rootOf("MNQZ6"));
        eq("ESZ6 root", "ES", RthHours.rootOf("ESZ6"));
        eq("slash and 2-digit year", "ES", RthHours.rootOf("/ESZ26"));
        eq("NQ root", "NQ", RthHours.rootOf("NQZ6"));
        eq("GC root", "GC", RthHours.rootOf("GCQ6"));
        eq("MGC root", "MGC", RthHours.rootOf("MGCZ6"));
        eq("CL root", "CL", RthHours.rootOf("CLZ6"));
        eq("MCL root", "MCL", RthHours.rootOf("MCLZ6"));
        eq("QM root", "QM", RthHours.rootOf("QMZ6"));
        eq("M2K root", "M2K", RthHours.rootOf("M2KZ6"));
        eq("lower case is accepted", "MNQ", RthHours.rootOf(" mnqz6 "));
        eq("no suffix is kept", "NQ", RthHours.rootOf("NQ"));
        eq("null is empty", "", RthHours.rootOf(null));

        eq("equity index hours", new RthHours(LocalTime.of(9, 30), LocalTime.of(16, 0)), RthHours.forSymbol("ESZ6").orElseThrow());
        eq("micro uses the same hours", RthHours.forSymbol("NQZ6").orElseThrow(), RthHours.forSymbol("MNQZ6").orElseThrow());
        eq("crude hours", new RthHours(LocalTime.of(9, 0), LocalTime.of(14, 30)), RthHours.forSymbol("CLZ6").orElseThrow());
        eq("gold hours", new RthHours(LocalTime.of(8, 20), LocalTime.of(13, 30)), RthHours.forSymbol("GCZ6").orElseThrow());
        check("unknown product has no hours", RthHours.forSymbol("ZNZ6").isEmpty());
        check("unknown product with a digit root", RthHours.forSymbol("6EZ6").isEmpty());

        eq("time 9:30", LocalTime.of(9, 30), RthHours.parseTime("9:30").orElseThrow());
        eq("time with spaces", LocalTime.of(16, 0), RthHours.parseTime(" 16 : 00 ").orElseThrow());
        check("time 24:00 is refused", RthHours.parseTime("24:00").isEmpty());
        check("time 9:75 is refused", RthHours.parseTime("9:75").isEmpty());
        check("time text is refused", RthHours.parseTime("open").isEmpty());
        check("null time is refused", RthHours.parseTime(null).isEmpty());

        var auto = RthHours.resolve(true, "CLZ6", "9:30", "16:00");
        eq("auto uses the table", new RthHours(LocalTime.of(9, 0), LocalTime.of(14, 30)), auto.hours());
        check("auto with a known product is no fallback", !auto.usedFallback());
        var typed = RthHours.resolve(false, "CLZ6", "8:00", "12:00");
        eq("custom uses the typed hours even for a known product", new RthHours(LocalTime.of(8, 0), LocalTime.of(12, 0)), typed.hours());
        var unknown = RthHours.resolve(true, "ZNZ6", "7:20", "14:00");
        eq("unknown product falls back to the typed hours", new RthHours(LocalTime.of(7, 20), LocalTime.of(14, 0)), unknown.hours());
        check("typed hours are not a fallback", !unknown.usedFallback());
        var bad = RthHours.resolve(false, "ZNZ6", "16:00", "9:30");
        eq("invalid typed hours fall back to equity hours", NQ_HOURS, bad.hours());
        check("and say so", bad.usedFallback());

        boolean threw = false;
        try { new RthHours(LocalTime.of(16, 0), LocalTime.of(9, 30)); } catch (IllegalArgumentException e) { threw = true; }
        check("hours that wrap past midnight are refused", threw);
    }

    // ------------------------------------------------------------------ trade dates
    private static void tradeDates() {
        var c = clock();
        eq("Sunday 18:00 belongs to Monday", LocalDate.of(2026, 10, 5), c.tradeDate(ny(2026, 10, 4, 18, 0)));
        eq("Sunday 17:59 is still Sunday (closed)", LocalDate.of(2026, 10, 4), c.tradeDate(ny(2026, 10, 4, 17, 59)));
        eq("Monday 17:59 is Monday", LocalDate.of(2026, 10, 5), c.tradeDate(ny(2026, 10, 5, 17, 59)));
        eq("Monday 18:00 is Tuesday", LocalDate.of(2026, 10, 6), c.tradeDate(ny(2026, 10, 5, 18, 0)));
        eq("Friday 16:59 is Friday", LocalDate.of(2026, 10, 9), c.tradeDate(ny(2026, 10, 9, 16, 59)));
        eq("evening of the last day of a month is the next month",
                LocalDate.of(2026, 10, 1), c.tradeDate(ny(2026, 9, 30, 18, 0)));

        // daylight saving: the session still starts at 18:00 wall-clock time
        eq("DST start night: Sunday 18:00 EDT", LocalDate.of(2026, 3, 9), c.tradeDate(ny(2026, 3, 8, 18, 0)));
        eq("DST end night: Sunday 18:00 EST", LocalDate.of(2026, 11, 2), c.tradeDate(ny(2026, 11, 1, 18, 0)));

        // the user's own zone does not matter: the same instant gives the same date
        long t = ny(2026, 10, 5, 3, 0);
        var berlin = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZoneId.of("Europe/Berlin"));
        eq("same instant seen from Berlin", c.tradeDate(t), c.tradeDate(berlin.toInstant().toEpochMilli()));

        eq("week key of a Wednesday is its Monday",
                SessionClock.weekKey(LocalDate.of(2026, 10, 5)), SessionClock.weekKey(LocalDate.of(2026, 10, 7)));
        check("next Monday starts a new week",
                SessionClock.weekKey(LocalDate.of(2026, 10, 9)) != SessionClock.weekKey(LocalDate.of(2026, 10, 12)));
        check("month keys differ across a month end",
                SessionClock.monthKey(LocalDate.of(2026, 9, 30)) != SessionClock.monthKey(LocalDate.of(2026, 10, 1)));
        eq("month key across a year end is consecutive",
                SessionClock.monthKey(LocalDate.of(2026, 12, 31)) + 1, SessionClock.monthKey(LocalDate.of(2027, 1, 1)));
    }

    // ------------------------------------------------------------------ regular hours
    private static void regularHours() {
        var c = clock();
        check("09:29 is not RTH", !c.isRth(ny(2026, 10, 5, 9, 29)));
        check("09:30 is RTH", c.isRth(ny(2026, 10, 5, 9, 30)));
        check("15:59 is RTH", c.isRth(ny(2026, 10, 5, 15, 59)));
        check("16:00 is not RTH", !c.isRth(ny(2026, 10, 5, 16, 0)));
        check("Saturday noon is not RTH", !c.isRth(ny(2026, 10, 3, 12, 0)));
        check("Sunday noon is not RTH", !c.isRth(ny(2026, 10, 4, 12, 0)));
        check("RTH opens at 09:30 in EDT", c.isRth(ny(2026, 10, 5, 9, 30)));
        check("RTH opens at 09:30 in EST too", c.isRth(ny(2026, 11, 2, 9, 30)) && !c.isRth(ny(2026, 11, 2, 9, 29)));
        eq("rth date is the calendar date", LocalDate.of(2026, 10, 5), c.rthDate(ny(2026, 10, 5, 10, 0)));
    }

    // ------------------------------------------------------------------ Globex
    private static void globex() {
        var m = new MedianCalc(clock(), Mode.GLOBEX);
        MedianCalc.Mids r;

        r = m.feed(ny(2026, 10, 4, 18, 0), 100, 90);                 // Sunday evening = Monday's session
        near("first bar: day", 95, r.day());
        near("first bar: week", 95, r.week());
        near("first bar: month", 95, r.month());

        r = m.feed(ny(2026, 10, 4, 18, 5), 110, 95);
        near("new high moves the median", 100, r.day());

        r = m.feed(ny(2026, 10, 5, 9, 30), 105, 85);                  // new low, same Globex day
        near("new low moves the median", 97.5, r.day());

        r = m.feed(ny(2026, 10, 5, 18, 0), 108, 100);                 // Tuesday's session starts
        near("new day starts from its own bar", 104, r.day());
        near("week keeps Monday's range", 97.5, r.week());
        near("month keeps Monday's range", 97.5, r.month());

        // Friday close, then Sunday evening: a new week but the same month
        m.feed(ny(2026, 10, 9, 16, 0), 120, 80);
        r = m.feed(ny(2026, 10, 11, 18, 0), 100, 98);
        near("Sunday evening starts a new day", 99, r.day());
        near("Sunday evening starts a new week", 99, r.week());
        near("the month goes on", 100, r.month());                     // 120 / 80 earlier -> 100

        // month end: the evening bar belongs to the next month
        r = m.feed(ny(2026, 10, 30, 12, 0), 130, 70);
        near("month range grows", 100, r.month());
        r = m.feed(ny(2026, 10, 30, 18, 0), 90, 88);                  // trade date Oct 31 (Friday): still October
        near("Oct 30 evening is still October", 100, r.month());
        r = m.feed(ny(2026, 11, 1, 18, 0), 95, 93);                   // Sunday evening -> Monday Nov 2
        near("a new month starts", 94, r.month());
    }

    // ------------------------------------------------------------------ RTH only
    private static void rthOnly() {
        var m = new MedianCalc(clock(), Mode.RTH);
        MedianCalc.Mids r;

        r = m.feed(ny(2026, 10, 4, 18, 0), 100, 90);                  // Sunday evening: no RTH bar yet
        near("before the first RTH bar the day is empty", Double.NaN, r.day());
        near("before the first RTH bar the week is empty", Double.NaN, r.week());

        r = m.feed(ny(2026, 10, 5, 9, 30), 100, 90);
        near("first RTH bar", 95, r.day());
        r = m.feed(ny(2026, 10, 5, 15, 55), 104, 88);
        near("RTH range grows", 96, r.day());

        r = m.feed(ny(2026, 10, 5, 16, 5), 200, 0);                   // after the close: ignored, value frozen
        near("frozen after the close", 96, r.day());
        near("week frozen too", 96, r.week());
        r = m.feed(ny(2026, 10, 6, 3, 0), 300, -50);
        near("still frozen overnight", 96, r.day());

        r = m.feed(ny(2026, 10, 6, 9, 30), 50, 40);                   // next day's open starts fresh
        near("next RTH day starts from its own bar", 45, r.day());
        near("week range now covers both RTH days", (104 + 40) / 2.0, r.week());     // high 104 (Mon), low 40 (Tue)
    }

    // ------------------------------------------------------------------ history request
    private static void history() {
        eq("15-minute bars: 24 sessions x 23 h / 15 min + 10%", 2428, History.barsForMonth(15, true));
        eq("5-minute bars", 7286, History.barsForMonth(5, true));
        check("15-minute bars cover 24 sessions of 23 hours", History.barsForMonth(15, true) >= 24 * 23 * 4);
        check("5-minute bars cover them too", History.barsForMonth(5, true) >= 24 * 23 * 12);
        check("5-minute needs about 3x the 15-minute count", Math.abs(History.barsForMonth(5, true) - 3 * History.barsForMonth(15, true)) <= 3);
        eq("1-minute bars", 36432, History.barsForMonth(1, true));
        check("and that stays within the cap", History.barsForMonth(1, true) <= History.CAP);
        check("an hour needs few bars", History.barsForMonth(60, true) < 1000);
        eq("daily bars are left to the platform", 40, History.barsForMonth(1440, false));
        eq("non-time bars are left to the platform", 40, History.barsForMonth(0, true));
    }

    // ------------------------------------------------------------------ fine series read at chart bar ends
    private static final class FineFeed implements Feed {
        final java.util.List<double[]> bars = new java.util.ArrayList<>();    // {start, end, high, low, complete}
        void add(long start, long minutes, double h, double l, boolean complete) {
            bars.add(new double[]{start, start + minutes * 60_000L, h, l, complete ? 1 : 0});
        }
        @Override public int size() { return bars.size(); }
        @Override public long start(int i) { return (long) bars.get(i)[0]; }
        @Override public long end(int i) { return (long) bars.get(i)[1]; }
        @Override public double high(int i) { return bars.get(i)[2]; }
        @Override public double low(int i) { return bars.get(i)[3]; }
        @Override public boolean complete(int i) { return bars.get(i)[4] == 1; }
    }

    private static void mapped() {
        var fine = new FineFeed();
        fine.add(ny(2026, 10, 5, 9, 30), 5, 100, 90, true);              // 9:30 - 9:35
        fine.add(ny(2026, 10, 5, 9, 35), 5, 110, 95, true);              // 9:35 - 9:40
        fine.add(ny(2026, 10, 5, 9, 40), 5, 105, 85, true);              // 9:40 - 9:45
        fine.add(ny(2026, 10, 5, 9, 45), 5, 500, 1, false);              // forming: must not count

        var m = new MappedSeries(new MedianCalc(clock(), Mode.GLOBEX));
        check("a chart bar that ended before the first fine bar closed has no value", Double.isNaN(m.at(fine, ny(2026, 10, 5, 9, 34)).day()));
        near("after the first 5-minute bar", 95, m.at(fine, ny(2026, 10, 5, 9, 35)).day());
        near("a 15-minute chart bar 9:30 - 9:45 sees the three closed bars", (110 + 85) / 2.0, m.at(fine, ny(2026, 10, 5, 9, 45)).day());
        near("the forming fine bar never counts", (110 + 85) / 2.0, m.at(fine, ny(2026, 10, 5, 9, 50)).day());
        eq("three fine bars consumed", 3, m.fedCount());
        near("asking an earlier chart bar again gives its own value", 95, m.at(fine, ny(2026, 10, 5, 9, 35)).day());

        fine.bars.get(3)[4] = 1;                                         // it closes
        near("once closed it counts", (500 + 1) / 2.0, m.at(fine, ny(2026, 10, 5, 9, 50)).day());

        // identical to the plain closed-bar series when the fine series IS the chart series
        var plain = new ClosedBarSeries(new MedianCalc(clock(), Mode.GLOBEX));
        var same = new MappedSeries(new MedianCalc(clock(), Mode.GLOBEX));
        var chart = new Fake();
        for (int i = 0; i < fine.size(); i++) chart.add(fine.start(i), fine.high(i), fine.low(i));
        for (int i = 0; i < fine.size(); i++) {
            near("same day value at " + i, plain.at(i, true, chart).day(), same.at(fine, fine.end(i)).day());
            near("same week value at " + i, plain.at(i, true, chart).week(), same.at(fine, fine.end(i)).week());
        }

        // a week and a month the chart never loaded: the fine series has them
        var long5 = new FineFeed();
        long5.add(ny(2026, 10, 1, 10, 0), 5, 120, 100, true);            // early in the month
        long5.add(ny(2026, 10, 6, 10, 0), 5, 110, 105, true);            // today
        var month = new MappedSeries(new MedianCalc(clock(), Mode.GLOBEX));
        var r = month.at(long5, ny(2026, 10, 6, 10, 15));                // a chart that only has today's bars asks
        near("month covers bars before the chart began", (120 + 100) / 2.0, r.month());
        near("day covers today only", (110 + 105) / 2.0, r.day());

        month.reset(new MedianCalc(clock(), Mode.GLOBEX));
        eq("reset starts again", 0, month.fedCount());
    }

    // ------------------------------------------------------------------ closed bars only
    private static final class Fake implements ClosedBarSeries.Bars {
        final java.util.List<double[]> bars = new java.util.ArrayList<>();      // {high, low}
        final java.util.List<Long> times = new java.util.ArrayList<>();
        void add(long t, double h, double l) { times.add(t); bars.add(new double[]{h, l}); }
        @Override public long time(int i) { return times.get(i); }
        @Override public double high(int i) { return bars.get(i)[0]; }
        @Override public double low(int i) { return bars.get(i)[1]; }
    }

    private static void closedOnly() {
        var series = new ClosedBarSeries(new MedianCalc(clock(), Mode.GLOBEX));
        var bars = new Fake();
        bars.add(ny(2026, 10, 5, 9, 30), 100, 90);
        bars.add(ny(2026, 10, 5, 9, 35), 110, 95);
        bars.add(ny(2026, 10, 5, 9, 40), 500, 1);                     // still forming: must not count

        near("first closed bar", 95, series.at(0, true, bars).day());
        near("second closed bar", 100, series.at(1, true, bars).day());
        near("forming bar holds the last closed value", 100, series.at(2, false, bars).day());
        near("a forming bar stays out of the week", 100, series.at(2, false, bars).week());
        eq("only closed bars were consumed", 2, series.fedCount());

        near("asking again changes nothing", 100, series.at(2, false, bars).day());
        near("a closed bar can be asked again", 95, series.at(0, true, bars).day());

        near("once the bar closes it counts", (500 + 1) / 2.0, series.at(2, true, bars).day());
        eq("three bars consumed", 3, series.fedCount());

        bars.add(ny(2026, 10, 5, 9, 45), 50, 40);
        near("the next forming bar holds the new value", 250.5, series.at(3, false, bars).day());

        series.reset(new MedianCalc(clock(), Mode.GLOBEX));
        eq("reset starts again", 0, series.fedCount());
        check("nothing known before the first closed bar", Double.isNaN(series.at(0, false, bars).day()));
        near("after a reset the first bar is fed again", 95, series.at(0, true, bars).day());

        // a long closed history is fed in one go when the last index is asked for
        var s2 = new ClosedBarSeries(new MedianCalc(clock(), Mode.GLOBEX));
        near("catching up over several closed bars", 250.5, s2.at(2, true, bars).day());
        eq("all three were fed", 3, s2.fedCount());
    }

    // ------------------------------------------------------------------ segments and the straight line
    private static void runs() {
        var m = new MedianCalc(clock(), Mode.GLOBEX);
        var a = m.feed(ny(2026, 10, 5, 9, 30), 100, 90);
        var b = m.feed(ny(2026, 10, 5, 9, 35), 110, 95);
        var c = m.feed(ny(2026, 10, 5, 18, 0), 108, 100);              // next Globex day
        eq("same day, same run", a.dayRun(), b.dayRun());
        check("next day is a new run", b.dayRun() != c.dayRun());
        eq("same week, same run", b.weekRun(), c.weekRun());
        eq("same month, same run", b.monthRun(), c.monthRun());

        var g = new MedianCalc(clock(), Mode.GLOBEX).feed(ny(2026, 10, 5, 9, 30), 1, 1);
        var r = new MedianCalc(clock(), Mode.RTH).feed(ny(2026, 10, 5, 9, 30), 1, 1);
        check("Globex and RTH runs never share an id", g.dayRun() != r.dayRun());

        var x = new MedianCalc(clock(), Mode.MIX);
        var night = x.feed(ny(2026, 10, 5, 8, 0), 100, 90);
        var open = x.feed(ny(2026, 10, 5, 9, 30), 120, 110);
        var close = x.feed(ny(2026, 10, 5, 16, 0), 118, 112);
        check("Mix: the open starts a new segment", night.dayRun() != open.dayRun());
        check("Mix: the close starts another", open.dayRun() != close.dayRun());
        eq("Mix: week keeps its run through the day", night.weekRun(), close.weekRun());
        eq("Period lookup: value", night.day(), night.value(Period.DAY));
        eq("Period lookup: run", night.monthRun(), night.run(Period.MONTH));
    }

    private static final class Recorder implements StraightLines.Writer {
        final java.util.Map<Integer, Double> day = new java.util.TreeMap<>(), week = new java.util.TreeMap<>(), month = new java.util.TreeMap<>();
        @Override public void write(int index, Period p, double value) {
            switch (p) { case DAY -> day.put(index, value); case WEEK -> week.put(index, value); case MONTH -> month.put(index, value); }
        }
    }

    private static void straight() {
        // Globex: a day grows over two bars, then the next day starts
        var series = new ClosedBarSeries(new MedianCalc(clock(), Mode.GLOBEX));
        var lines = new StraightLines();
        var rec = new Recorder();
        var bars = new Fake();
        bars.add(ny(2026, 10, 5, 9, 30), 100, 90);                      // day mid 95
        bars.add(ny(2026, 10, 5, 9, 35), 110, 95);                      // day mid 100
        bars.add(ny(2026, 10, 5, 18, 0), 108, 100);                     // new day: mid 104, week and month unchanged
        for (int i = 0; i < 3; i++) lines.onClosedBar(i, series.at(i, true, bars), rec);

        eq("first day is flat at its latest value (bar 0)", 100.0, rec.day.get(0));
        eq("first day is flat at its latest value (bar 1)", 100.0, rec.day.get(1));
        eq("the next day has its own level", 104.0, rec.day.get(2));
        eq("the week is one flat line over all three bars", java.util.List.of(100.0, 100.0, 100.0), new java.util.ArrayList<>(rec.week.values()));
        eq("the month is one flat line", 3, rec.month.size());

        // RTH only: nothing before the first regular-hours bar
        var rthSeries = new ClosedBarSeries(new MedianCalc(clock(), Mode.RTH));
        var rthLines = new StraightLines();
        var rrec = new Recorder();
        var rbars = new Fake();
        rbars.add(ny(2026, 10, 4, 18, 0), 100, 90);                     // Sunday evening: not RTH
        rbars.add(ny(2026, 10, 5, 9, 30), 100, 90);                     // 95
        rbars.add(ny(2026, 10, 5, 16, 5), 500, 0);                      // after the close: frozen at 95, ignored
        for (int i = 0; i < 3; i++) rthLines.onClosedBar(i, rthSeries.at(i, true, rbars), rrec);
        check("no value before the first RTH bar", !rrec.day.containsKey(0));
        eq("RTH bar", 95.0, rrec.day.get(1));
        eq("frozen bar belongs to the same RTH segment", 95.0, rrec.day.get(2));

        // Mix: overnight grows (rewritten flat), the RTH segment and the after-close segment have their own levels
        var mix = new ClosedBarSeries(new MedianCalc(clock(), Mode.MIX));
        var mixLines = new StraightLines();
        var mrec = new Recorder();
        var mbars = new Fake();
        mbars.add(ny(2026, 10, 4, 18, 0), 100, 90);                     // overnight 95
        mbars.add(ny(2026, 10, 4, 18, 5), 110, 95);                     // overnight 100
        mbars.add(ny(2026, 10, 5, 9, 30), 120, 110);                    // RTH 115
        mbars.add(ny(2026, 10, 5, 16, 0), 118, 112);                    // Globex day (120 + 90) / 2 = 105
        for (int i = 0; i < 4; i++) mixLines.onClosedBar(i, mix.at(i, true, mbars), mrec);
        eq("Mix straight line", java.util.List.of(100.0, 100.0, 115.0, 105.0), new java.util.ArrayList<>(mrec.day.values()));

        // reset forgets the segments
        mixLines.reset();
        var again = new Recorder();
        mixLines.onClosedBar(0, mix.result(0), again);
        eq("after a reset only the new bar is written", 1, again.day.size());
    }

    // ------------------------------------------------------------------ Mix
    private static void mix() {
        var m = new MedianCalc(clock(), Mode.MIX);
        MedianCalc.Mids r;

        r = m.feed(ny(2026, 10, 4, 18, 0), 100, 90);                  // overnight: Globex
        near("overnight follows Globex", 95, r.day());

        r = m.feed(ny(2026, 10, 5, 9, 30), 120, 110);                 // open: RTH range starts fresh
        near("at the open the daily line switches to the RTH range", 115, r.day());
        near("week stays Globex during RTH", (120 + 90) / 2.0, r.week());

        r = m.feed(ny(2026, 10, 5, 12, 0), 125, 108);
        near("RTH range grows", (125 + 108) / 2.0, r.day());

        r = m.feed(ny(2026, 10, 5, 16, 0), 118, 112);                 // after the close: back to Globex of the day
        near("after the close the daily line returns to Globex", (125 + 90) / 2.0, r.day());
        near("week is Globex", (125 + 90) / 2.0, r.week());
        near("month is Globex", (125 + 90) / 2.0, r.month());

        r = m.feed(ny(2026, 10, 5, 18, 0), 119, 117);                 // next Globex day
        near("next Globex day starts fresh", 118, r.day());

        r = m.feed(ny(2026, 10, 6, 9, 30), 130, 126);
        near("next day's open switches to that day's RTH range", 128, r.day());
    }
}

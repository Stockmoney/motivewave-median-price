package median_price;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.NVP;
import com.motivewave.platform.sdk.common.Settings;
import com.motivewave.platform.sdk.common.desc.BarSizeDescriptor;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.DiscreteDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.common.desc.StringDescriptor;
import com.motivewave.platform.sdk.common.desc.ValueDescriptor;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import median_price.audit.AuditLog;
import median_price.core.ClosedBarSeries;
import median_price.core.Feed;
import median_price.core.MappedSeries;
import median_price.core.MedianCalc;
import median_price.core.Mode;
import median_price.core.Period;
import median_price.core.RthHours;
import median_price.core.SessionClock;
import median_price.core.SourcePick;
import median_price.core.StraightLines;

/**
 * Median Price
 * ------------
 * The median price, (high + low) / 2, of the CURRENT day, week and month, drawn as three lines that end at the
 * last candle. The range is measured over Globex, over US regular hours, or a mix of both (see {@link Mode});
 * "Show both" draws a Globex set and an RTH set side by side. Lines are step lines (the median as it was at
 * each bar) or, with "Straight line", flat segments at their latest value. Only CLOSED bars count: the bar that
 * is still forming does not move the lines.
 *
 * <p>Display only: a plain Study with no access to orders. All the calculation lives in {@code core} and has
 * no dependency on MotiveWave.
 */
@StudyHeader(
        namespace = "com.zentrader",
        id = "MEDIAN_PRICE",
        rb = "median_price.nls.strings",
        name = "STUDY_NAME",
        desc = "STUDY_DESC",
        menu = "MENU_GENERAL",
        overlay = true,
        studyOverlay = true,
        requiresBarUpdates = true
)
public class MedianPrice extends Study {

    static final String VERSION = "0.3.4";

    /** Plotted values: the main set, and the RTH set that "Show both" adds. */
    private enum Values { DAY, WEEK, MONTH, DAY_RTH, WEEK_RTH, MONTH_RTH }

    // setting keys
    private static final String HELPER_BAR = "helperBar";
    private static final String SESSION = "session";
    private static final String SHOW_BOTH = "showBoth";
    private static final String STRAIGHT = "straight";
    private static final String RTH_AUTO = "rthAuto";
    private static final String RTH_OPEN = "rthOpen";
    private static final String RTH_CLOSE = "rthClose";
    private static final String DAY_PATH = "dayPath";
    private static final String WEEK_PATH = "weekPath";
    private static final String MONTH_PATH = "monthPath";
    private static final String DAY_RTH_PATH = "dayRthPath";
    private static final String WEEK_RTH_PATH = "weekRthPath";
    private static final String MONTH_RTH_PATH = "monthRthPath";

    /** One calculation drawn on the chart: its own median series, straight-line tracker and plotted keys. */
    private static final class Layer {
        final MappedSeries series;
        final StraightLines straight = new StraightLines();
        final Values[] keys;
        final List<MedianCalc.Mids> chartMids = new ArrayList<>();      // value at the end of every closed chart bar
        MedianCalc.Mids lastMids = ClosedBarSeries.EMPTY;

        Layer(MedianCalc calc, Values[] keys) {
            this.series = new MappedSeries(calc);
            this.keys = keys;
        }
    }

    /** The platform's data series as a {@link Feed}. A zero interval means "ask the series for each bar's end". */
    private static final class PlatformFeed implements Feed {
        private final DataSeries ds;
        private final long intervalMillis;

        PlatformFeed(DataSeries ds, long intervalMillis) {
            this.ds = ds;
            this.intervalMillis = intervalMillis;
        }

        @Override public int size() { return ds.size(); }
        @Override public long start(int i) { return ds.getStartTime(i); }
        @Override public long end(int i) { return intervalMillis > 0 ? ds.getStartTime(i) + intervalMillis : ds.getEndTime(i); }
        @Override public double high(int i) { return ds.getHigh(i); }
        @Override public double low(int i) { return ds.getLow(i); }
        @Override public boolean complete(int i) { return ds.isBarComplete(i); }
    }

    private List<Layer> layers = new ArrayList<>();
    private Feed fine;                                  // where the ranges are read from: the helper series, or the chart's own bars
    private boolean straightLine;
    private String lastLogged = "";

    @Override
    public void initialize(Defaults defaults) {
        var sd = createSD();

        var general = sd.addTab(get("TAB_GENERAL"));
        var session = general.addGroup(get("GRP_SESSION"));
        session.addRow(new DiscreteDescriptor(SESSION, get("LBL_SESSION"), Mode.GLOBEX.name(), List.of(
                new NVP(get("OPT_GLOBEX"), Mode.GLOBEX.name()),
                new NVP(get("OPT_RTH"), Mode.RTH.name()),
                new NVP(get("OPT_MIX"), Mode.MIX.name()))));
        session.addRow(new BooleanDescriptor(SHOW_BOTH, get("LBL_SHOW_BOTH"), false));
        var display = general.addGroup(get("GRP_DISPLAY"));
        display.addRow(new BooleanDescriptor(STRAIGHT, get("LBL_STRAIGHT"), false));
        var rth = general.addGroup(get("GRP_RTH"));
        rth.addRow(new DiscreteDescriptor(RTH_AUTO, get("LBL_RTH_AUTO"), "AUTO", List.of(
                new NVP(get("OPT_AUTO"), "AUTO"),
                new NVP(get("OPT_CUSTOM"), "CUSTOM"))));
        rth.addRow(new StringDescriptor(RTH_OPEN, get("LBL_RTH_OPEN"), "09:30"));
        rth.addRow(new StringDescriptor(RTH_CLOSE, get("LBL_RTH_CLOSE"), "16:00"));

        // The week and month ranges are read from a finer series that the platform loads for the whole month,
        // so they stay right however few bars the chart itself has loaded (5 minutes suits every product).
        general.addGroup(get("GRP_ADVANCED")).addRow(new BarSizeDescriptor(HELPER_BAR, get("LBL_HELPER_BAR"),
                com.motivewave.platform.sdk.common.BarSize.getBarSize(com.motivewave.platform.sdk.common.Enums$BarSizeType.LINEAR, 5)));

        var lines = sd.addTab(get("TAB_LINES"));
        var day = lines.addGroup(get("GRP_DAY"));
        day.addRow(path(DAY_PATH, "LBL_DAY", new Color(220, 50, 47), null));
        day.addRow(path(DAY_RTH_PATH, "LBL_DAY_RTH", new Color(255, 140, 0), DASHED));
        var week = lines.addGroup(get("GRP_WEEK"));
        week.addRow(path(WEEK_PATH, "LBL_WEEK", new Color(38, 139, 210), null));
        week.addRow(path(WEEK_RTH_PATH, "LBL_WEEK_RTH", new Color(0, 190, 190), DASHED));
        var month = lines.addGroup(get("GRP_MONTH"));
        month.addRow(path(MONTH_PATH, "LBL_MONTH", new Color(150, 150, 150), null));
        month.addRow(path(MONTH_RTH_PATH, "LBL_MONTH_RTH", new Color(205, 205, 205), DASHED));

        // the small panel behind the gear icon in the chart legend (its "All Settings" button opens everything)
        sd.addQuickSettings(SESSION, SHOW_BOTH, STRAIGHT, DAY_PATH, WEEK_PATH, MONTH_PATH,
                DAY_RTH_PATH, WEEK_RTH_PATH, MONTH_RTH_PATH);

        var rd = createRD();
        rd.setLabelPrefix("Median");
        rd.declarePath(Values.DAY, DAY_PATH);
        rd.declarePath(Values.WEEK, WEEK_PATH);
        rd.declarePath(Values.MONTH, MONTH_PATH);
        rd.declarePath(Values.DAY_RTH, DAY_RTH_PATH);
        rd.declarePath(Values.WEEK_RTH, WEEK_RTH_PATH);
        rd.declarePath(Values.MONTH_RTH, MONTH_RTH_PATH);
        rd.exportValue(new ValueDescriptor(Values.DAY, get("VAL_DAY"), new String[]{}));
        rd.exportValue(new ValueDescriptor(Values.WEEK, get("VAL_WEEK"), new String[]{}));
        rd.exportValue(new ValueDescriptor(Values.MONTH, get("VAL_MONTH"), new String[]{}));
        rd.exportValue(new ValueDescriptor(Values.DAY_RTH, get("VAL_DAY_RTH"), new String[]{}));
        rd.exportValue(new ValueDescriptor(Values.WEEK_RTH, get("VAL_WEEK_RTH"), new String[]{}));
        rd.exportValue(new ValueDescriptor(Values.MONTH_RTH, get("VAL_MONTH_RTH"), new String[]{}));
    }

    private static final float[] DASHED = {8f, 5f};

    private PathDescriptor path(String key, String labelKey, Color color, float[] dash) {
        var p = new PathDescriptor(key, get(labelKey), color, 1.0f, dash);
        p.setSupportsDisable(true);
        return p;
    }

    // ==================== calculation ====================
    /**
     * The platform loads history for a chart only as far back as the chart needs it (a 5-minute chart shows a day or
     * two), and the helper series then reaches no further: the week and month medians were computed from a day or two
     * of bars (journal 7 Oct 2026 21:28: month = week = 31380.38, "helper series (331 bars) from 2026-10-06").
     * Asking for data from a week before the start of the month makes the platform load all of it.
     */
    @Override
    public Long getMinStartTime(DataContext ctx) {
        return java.time.ZonedDateTime.now(SessionClock.NEW_YORK).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(SessionClock.NEW_YORK).minusDays(7).toInstant().toEpochMilli();
    }

    @Override
    protected synchronized void precalculate(DataContext ctx) {
        // a full recalculation (first load, new settings, new data) always starts from the first bar
        Settings s = getSettings();
        Mode mode = parseMode(s.getString(SESSION, Mode.GLOBEX.name()));
        boolean both = s.getBoolean(SHOW_BOTH, false);
        straightLine = s.getBoolean(STRAIGHT, false);
        boolean auto = !"CUSTOM".equals(s.getString(RTH_AUTO, "AUTO"));
        String symbol = ctx.getInstrument().getSymbol();
        var resolved = RthHours.resolve(auto, symbol, s.getString(RTH_OPEN, "09:30"), s.getString(RTH_CLOSE, "16:00"));
        var clock = new SessionClock(SessionClock.NEW_YORK, resolved.hours());

        // The source first: a day, week or month that opened before its first bar is only partly known, and the
        // calculation leaves such a period blank (no line) instead of drawing a plausible but wrong median.
        fine = chooseSource(ctx);
        long dataStart = fine.size() > 0 ? fine.start(0) : Long.MAX_VALUE;
        var next = new ArrayList<Layer>();
        if (both) {
            next.add(new Layer(new MedianCalc(clock, Mode.GLOBEX, dataStart), new Values[]{Values.DAY, Values.WEEK, Values.MONTH}));
            next.add(new Layer(new MedianCalc(clock, Mode.RTH, dataStart), new Values[]{Values.DAY_RTH, Values.WEEK_RTH, Values.MONTH_RTH}));
        } else {
            next.add(new Layer(new MedianCalc(clock, mode, dataStart), new Values[]{Values.DAY, Values.WEEK, Values.MONTH}));
        }
        layers = next;
        lastLogged = "";
        AuditLog.log("SETUP", "v" + VERSION + " " + symbol + " mode=" + (both ? "GLOBEX+RTH (show both)" : mode)
                + " line=" + (straightLine ? "straight" : "step") + " rth=" + resolved.hours()
                + (resolved.usedFallback() ? " (typed hours not valid: equity hours used)" : "")
                + " chart bars=" + ctx.getDataSeries().size() + " | range data: " + describe(fine, ctx));
    }

    /** Which series the ranges are read from, for the journal. */
    private String sourceText = "";

    /**
     * The series the ranges are read from. Two candidates: the helper series (5-minute bars, attached only to an
     * indicator that was created with its bar-size setting - the setting is also written explicitly, so an older
     * indicator gets it the next time the workspace loads) and the chart's own bars. The platform loads a different
     * amount of history for each depending on the chart (see {@link SourcePick}), so the first one that reaches back
     * to the start of the month is used; when neither does, the one that reaches back furthest, and the calculation
     * leaves the periods it cannot know blank.
     */
    private Feed chooseSource(DataContext ctx) {
        var bs = getSettings().getBarSize(HELPER_BAR);
        if (bs == null) bs = com.motivewave.platform.sdk.common.BarSize.getBarSize(com.motivewave.platform.sdk.common.Enums$BarSizeType.LINEAR, 5);
        getSettings().setBarSize(HELPER_BAR, bs);
        DataSeries helper = null;
        try {
            helper = ctx.getDataSeries(bs);
        } catch (RuntimeException e) {
            AuditLog.log("ERROR", "helper series: " + e);
        }
        long interval = bs.getIntervalMillis();
        DataSeries chart = ctx.getDataSeries();
        PlatformFeed helperFeed = helper != null && helper.size() > 0 && interval > 0 ? new PlatformFeed(helper, interval) : null;
        PlatformFeed chartFeed = chart != null && chart.size() > 0 ? new PlatformFeed(chart, 0) : null;
        if (chartFeed == null) {
            sourceText = helperFeed == null ? "none" : "helper series only";
            return helperFeed != null ? helperFeed : new PlatformFeed(chart, 0);
        }

        var clock = new SessionClock(SessionClock.NEW_YORK, null);
        long newest = chart.getStartTime(chart.size() - 1);
        long need = clock.monthStart(SessionClock.monthKey(clock.tradeDate(newest)));
        List<PlatformFeed> candidates = new ArrayList<>();
        candidates.add(helperFeed);
        candidates.add(chartFeed);
        int pick = SourcePick.best(candidates, need);
        sourceText = "month opened " + fmt(need) + "; helper " + (helperFeed == null ? "not attached" : helperFeed.size() + " bars from " + fmt(helperFeed.start(0)))
                + "; chart " + chartFeed.size() + " bars from " + fmt(chartFeed.start(0)) + " -> using " + (pick == 0 ? "the helper series" : "the CHART bars");
        return candidates.get(pick < 0 ? 1 : pick);
    }

    private static String fmt(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(SessionClock.NEW_YORK).toLocalDateTime().withNano(0).toString();
    }

    /** What the ranges are read from, and whether it reaches back to the start of the month. */
    private String describe(Feed source, DataContext ctx) {
        if (source.size() == 0) return "none";
        var first = java.time.Instant.ofEpochMilli(source.start(0)).atZone(SessionClock.NEW_YORK).toLocalDateTime();
        var chart = ctx.getDataSeries();
        var now = java.time.Instant.ofEpochMilli(chart.getStartTime(chart.size() - 1)).atZone(SessionClock.NEW_YORK).toLocalDateTime();
        var monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay().minusHours(6);       // the session of the 1st opens the evening before
        String text = sourceText + "; source starts " + first;
        if (first.isAfter(monthStart)) text += " - WARNING: the data starts after the month began: the month (and the weeks and days that began before "
                + first + ") are left BLANK, not guessed";
        return text;
    }

    @Override
    protected synchronized void calculate(int index, DataContext ctx) {
        // synchronized together with precalculate(): while a chart (re)loads its history the platform calls both from
        // different threads within milliseconds; precalculate() replaces the layers and clears their lists, and a
        // calculate() running through them read a half-cleared list (NullPointerException on 7 Oct 2026, 20:02:38).
        DataSeries bars = ctx.getDataSeries();
        if (layers.isEmpty() || fine == null) precalculate(ctx);
        boolean complete = bars.isBarComplete(index);
        // The platform may ask for the newest bar alone (e.g. after the workspace loads), so every call first
        // catches up on all closed bars before it: closed bars are taken in order, each reading the ranges as
        // they stood when that bar closed.
        int lastClosed = complete ? index : index - 1;
        for (Layer layer : layers) {
            for (int j = layer.chartMids.size(); j <= lastClosed; j++) {
                var mids = layer.series.at(fine, bars.getEndTime(j));
                if (mids == null) mids = ClosedBarSeries.EMPTY;      // never happens with the lock above; costs nothing
                layer.chartMids.add(mids);
                if (straightLine) {
                    // the tracker re-writes the earlier bars of a segment when its value moves
                    layer.straight.onClosedBar(j, mids, (i, period, value) -> put(bars, i, layer.keys[period.ordinal()], value));
                } else {
                    for (Period p : Period.values()) put(bars, j, layer.keys[p.ordinal()], mids.value(p));
                }
            }
            if (complete) {
                layer.lastMids = layer.chartMids.get(index);
            } else {
                // the forming bar holds the value of the last closed bar
                var held = layer.chartMids.isEmpty() ? ClosedBarSeries.EMPTY : layer.chartMids.get(layer.chartMids.size() - 1);
                layer.lastMids = held;
                for (Period p : Period.values()) put(bars, index, layer.keys[p.ordinal()], held.value(p));
            }
        }
        if (index == bars.size() - 1) logLast(bars, index);
    }

    private static void put(DataSeries bars, int index, Values key, double value) {
        if (!Double.isNaN(value)) bars.setDouble(index, key, value);
    }

    /** The values on the newest bar, written to the journal whenever they change. */
    private void logLast(DataSeries bars, int index) {
        var text = new StringBuilder();
        for (Layer layer : layers) {
            var m = layer.lastMids;
            text.append(String.format(Locale.ROOT, "[day=%s week=%s month=%s] ", num(m.day()), num(m.week()), num(m.month())));
        }
        text.append(String.format(Locale.ROOT, "#%x closed-mids=%d closed=%b bar=%s", System.identityHashCode(this) & 0xffff,
                layers.isEmpty() ? -1 : layers.get(0).chartMids.size(), bars.isBarComplete(index),
                java.time.Instant.ofEpochMilli(bars.getStartTime(index)).atZone(SessionClock.NEW_YORK)
                        .toLocalDateTime().withNano(0)));
        String line = text.toString();
        if (!line.equals(lastLogged)) {
            lastLogged = line;
            AuditLog.log("VALUES", line);
        }
    }

    private static String num(double v) { return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v); }

    private static Mode parseMode(String text) {
        try {
            return Mode.valueOf(text);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Mode.GLOBEX;
        }
    }
}

package median_price.core;

import java.util.List;

/**
 * Chooses which series the week and month ranges are read from. The platform hands over history only as far as
 * it decides to load it, and how far differs by chart (7 Oct 2026: the 5-minute helper series reached back to
 * 24 Sep on a 5-minute chart but only to 6 Oct on a 1-minute chart, while the 1-minute chart's own bars reached 24 Sep).
 * So no source is trusted by kind: the first one that reaches back to the start of the month is used, in the order
 * given (the preferred source first); when none does, the one that reaches back furthest.
 */
public final class SourcePick {

    private SourcePick() { }

    /** True when data that begins at {@code dataStart} covers a period that opened at {@code periodStart}. */
    public static boolean covers(long dataStart, long periodStart) {
        return dataStart <= periodStart + MedianCalc.SLACK_MS;
    }

    /**
     * @param candidates the series, preferred first (null and empty ones are skipped)
     * @param needStart  when the month that must be covered opened
     * @return the index of the chosen candidate, or -1 when there is no usable series
     */
    public static int best(List<? extends Feed> candidates, long needStart) {
        int furthest = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Feed f = candidates.get(i);
            if (f == null || f.size() == 0) continue;
            if (covers(f.start(0), needStart)) return i;
            if (furthest < 0 || f.start(0) < candidates.get(furthest).start(0)) furthest = i;
        }
        return furthest;
    }
}

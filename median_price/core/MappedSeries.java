package median_price.core;

/**
 * Median values taken from a FINE series (e.g. 5-minute bars that reach back over the whole month) and read
 * at the end of each chart bar. For a chart bar that closes at time T the value is the median after the last
 * fine bar that had closed by T; so the lines are exact at any chart bar size, and the week and month cover
 * every bar of the fine series however few bars the chart itself has loaded.
 *
 * <p>Only closed fine bars are used. Calls are idempotent and may repeat for the same chart bar.
 */
public final class MappedSeries {

    private MedianCalc calc;
    private final java.util.List<MedianCalc.Mids> fed = new java.util.ArrayList<>();

    public MappedSeries(MedianCalc calc) {
        this.calc = calc;
    }

    public void reset(MedianCalc newCalc) {
        calc = newCalc;
        fed.clear();
    }

    /** Number of fine bars consumed so far. */
    public int fedCount() { return fed.size(); }

    /** The median values after the last closed fine bar that ended at or before {@code chartBarEnd}. */
    public MedianCalc.Mids at(Feed fine, long chartBarEnd) {
        int j = lastClosedEndingBy(fine, chartBarEnd);
        if (j < 0) return ClosedBarSeries.EMPTY;
        while (fed.size() <= j) {
            int i = fed.size();
            fed.add(calc.feed(fine.start(i), fine.high(i), fine.low(i)));
        }
        return fed.get(j);
    }

    /** Binary search: the largest index of a closed bar whose end is at or before {@code time}, or -1. */
    static int lastClosedEndingBy(Feed fine, long time) {
        int n = fine.size();
        if (n > 0 && !fine.complete(n - 1)) n--;                  // the newest bar may still be forming
        int lo = 0, hi = n - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (fine.end(mid) <= time) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found;
    }
}

package median_price.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Median values per bar index, using CLOSED bars only: the high / low of the bar that is still forming never
 * enters the median. While a bar forms, the line simply holds the value of the last closed bar, so it
 * reaches the last candle without moving inside it; when the bar closes the next call includes it.
 *
 * <p>Calls are idempotent: asking again for an index that was already fed returns the stored value, so the
 * platform may recalculate a bar as often as it likes.
 */
public final class ClosedBarSeries {

    /** The bars of the platform's data series. */
    public interface Bars {
        long time(int index);
        double high(int index);
        double low(int index);
    }

    public static final MedianCalc.Mids EMPTY = new MedianCalc.Mids(Double.NaN, Double.NaN, Double.NaN, -1, -1, -1);

    private MedianCalc calc;
    private final List<MedianCalc.Mids> fed = new ArrayList<>();

    public ClosedBarSeries(MedianCalc calc) {
        this.calc = calc;
    }

    /** Start again from the first bar with a new calculation (settings changed, data reloaded). */
    public void reset(MedianCalc newCalc) {
        calc = newCalc;
        fed.clear();
    }

    /** Number of closed bars consumed so far. */
    public int fedCount() { return fed.size(); }

    /** The stored result of a closed bar. */
    public MedianCalc.Mids result(int index) { return fed.get(index); }

    public MedianCalc.Mids at(int index, boolean complete, Bars bars) {
        if (complete) {
            while (fed.size() <= index) {
                int i = fed.size();
                fed.add(calc.feed(bars.time(i), bars.high(i), bars.low(i)));
            }
            return fed.get(index);
        }
        if (index < fed.size()) return fed.get(index);      // asked again for a bar that is already closed
        return fed.isEmpty() ? EMPTY : fed.get(fed.size() - 1);
    }
}

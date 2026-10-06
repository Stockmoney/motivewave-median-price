package median_price.core;

/**
 * The "Straight line" display. A step line shows the median as it was at every bar; a straight line draws each
 * line SEGMENT (see {@link MedianCalc.Mids}) flat at the latest value that segment has reached. Because that value
 * is only known once the segment's newest bar closes, the bars of the segment before it are re-written with it.
 *
 * <p>Feed closed bars in order; the {@link Writer} receives every plotted value that must be (re)written.
 */
public final class StraightLines {

    /** Receives a value for a bar index of one period's line. */
    public interface Writer {
        void write(int index, Period period, double value);
    }

    private final long[] run = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
    private final int[] start = new int[3];
    private final double[] last = {Double.NaN, Double.NaN, Double.NaN};

    public void reset() {
        for (int i = 0; i < 3; i++) { run[i] = Long.MIN_VALUE; start[i] = 0; last[i] = Double.NaN; }
    }

    public void onClosedBar(int index, MedianCalc.Mids mids, Writer out) {
        for (Period p : Period.values()) {
            int k = p.ordinal();
            long id = mids.run(p);
            double v = mids.value(p);
            if (id != run[k]) { run[k] = id; start[k] = index; last[k] = Double.NaN; }
            if (Double.isNaN(v)) continue;
            if (Double.isNaN(last[k]) || v != last[k]) {
                for (int j = start[k]; j <= index; j++) out.write(j, p, v);      // the value moved: the whole segment follows
            } else {
                out.write(index, p, v);                                         // unchanged: only the new bar
            }
            last[k] = v;
        }
    }
}

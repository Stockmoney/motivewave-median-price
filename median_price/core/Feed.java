package median_price.core;

/** A time-ordered series of bars (the platform's data series, or a fake one in tests). */
public interface Feed {
    int size();
    long start(int index);
    long end(int index);
    double high(int index);
    double low(int index);
    boolean complete(int index);
}

package median_price.core;

/** How many bars of a given size cover one month of Globex trading. */
public final class History {

    private static final int TRADING_DAYS = 24;                 // up to 23 sessions in a month, plus the one that opened the month
    private static final int MINUTES_PER_DAY = 23 * 60;          // Globex: 18:00 - 17:00 New York
    private static final int MARGIN_PERCENT = 10;
    public static final int CAP = 50_000;                        // a 1-minute chart still fits; a tick chart does not get an absurd request

    private History() { }

    /**
     * @param barMinutes length of one bar in minutes (0 or less when it is not a time bar)
     * @param intraday   whether the bars are shorter than a day
     * @return the number of bars to ask the platform for; a modest default for bars that are not intraday time bars
     */
    public static int barsForMonth(int barMinutes, boolean intraday) {
        if (!intraday || barMinutes <= 0) return 40;              // daily, weekly, tick or range bars: leave it to the platform
        long minutes = (long) TRADING_DAYS * MINUTES_PER_DAY;
        long bars = (minutes + barMinutes - 1) / barMinutes;
        bars += bars * MARGIN_PERCENT / 100;
        return (int) Math.min(bars, CAP);
    }
}

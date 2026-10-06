package median_price.core;

import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regular trading hours of an exchange product, in exchange time (New York). Chosen by the product's root
 * symbol (MNQZ6 -> MNQ); products that are not in the table must be given hours by the user.
 */
public record RthHours(LocalTime open, LocalTime close) {

    private static final RthHours EQUITY_INDEX = new RthHours(LocalTime.of(9, 30), LocalTime.of(16, 0));
    private static final RthHours CRUDE = new RthHours(LocalTime.of(9, 0), LocalTime.of(14, 30));
    private static final RthHours GOLD = new RthHours(LocalTime.of(8, 20), LocalTime.of(13, 30));

    private static final Map<String, RthHours> BY_ROOT = Map.ofEntries(
            Map.entry("ES", EQUITY_INDEX), Map.entry("MES", EQUITY_INDEX),
            Map.entry("NQ", EQUITY_INDEX), Map.entry("MNQ", EQUITY_INDEX),
            Map.entry("YM", EQUITY_INDEX), Map.entry("MYM", EQUITY_INDEX),
            Map.entry("RTY", EQUITY_INDEX), Map.entry("M2K", EQUITY_INDEX),
            Map.entry("CL", CRUDE), Map.entry("MCL", CRUDE), Map.entry("QM", CRUDE),
            Map.entry("GC", GOLD), Map.entry("MGC", GOLD));

    /** root letters, futures month code, 1-4 digit year: MNQZ6, /ESZ26, GCQ6 */
    private static final Pattern CONTRACT = Pattern.compile("^/?([A-Z0-9]{1,4}?)[FGHJKMNQUVXZ]\\d{1,4}$");

    public RthHours {
        if (!open.isBefore(close)) throw new IllegalArgumentException("RTH must open before it closes (same day)");
    }

    /** "MNQZ6" -> "MNQ"; a symbol without a contract suffix is returned upper-cased as is. */
    public static String rootOf(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        var m = CONTRACT.matcher(s);
        return m.matches() ? m.group(1) : (s.startsWith("/") ? s.substring(1) : s);
    }

    /** Hours for a symbol such as "MNQZ6", or empty when the product is not known. */
    public static Optional<RthHours> forSymbol(String symbol) {
        return Optional.ofNullable(BY_ROOT.get(rootOf(symbol)));
    }

    /** "9:30" or "09:30" -> 09:30; anything else (or out of range) -> empty. */
    public static Optional<LocalTime> parseTime(String text) {
        if (text == null) return Optional.empty();
        var m = Pattern.compile("^\\s*(\\d{1,2})\\s*:\\s*(\\d{2})\\s*$").matcher(text);
        if (!m.matches()) return Optional.empty();
        int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
        return h <= 23 && mi <= 59 ? Optional.of(LocalTime.of(h, mi)) : Optional.empty();
    }

    /**
     * The hours to use. With {@code auto} and a known product they come from the table; otherwise from the
     * typed open / close times. If those are not valid hours either, the equity-index hours are used and
     * {@code usedFallback} is set in the result so the study can say so.
     */
    public static Resolved resolve(boolean auto, String symbol, String openText, String closeText) {
        if (auto) {
            var known = forSymbol(symbol);
            if (known.isPresent()) return new Resolved(known.get(), false);
        }
        var open = parseTime(openText);
        var close = parseTime(closeText);
        if (open.isPresent() && close.isPresent() && open.get().isBefore(close.get())) {
            return new Resolved(new RthHours(open.get(), close.get()), false);
        }
        return new Resolved(EQUITY_INDEX, true);
    }

    public record Resolved(RthHours hours, boolean usedFallback) { }

    public boolean contains(LocalTime t) {
        return !t.isBefore(open) && t.isBefore(close);
    }
}

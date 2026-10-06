package median_price.core;

/** Which trading session the range of a period is measured over. */
public enum Mode {
    /** Full electronic session: the CME trading day runs 18:00 - 17:00 New York time. */
    GLOBEX,
    /** Regular (US pit) hours only; outside them the line stays frozen. */
    RTH,
    /** The daily line follows Globex outside regular hours and the RTH range during them; week and month stay Globex. */
    MIX
}

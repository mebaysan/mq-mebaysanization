package com.baysansoft.mqmanager.logs;

import java.util.Locale;

/**
 * Which end of the time range a page of log lines is read from.
 *
 * <p>This decides <strong>presentation only</strong>. A limited read always returns the most recent
 * matching lines; see {@link LogBuffer#recent(LogLevel, String, java.time.Instant, java.time.Instant,
 * SortOrder, int)} for why.
 */
public enum SortOrder {

    /** Most recent line first. A log tail is read from the top, so this is the default. */
    NEWEST_FIRST,

    /** Oldest first, for reading a window in the order things actually happened. */
    OLDEST_FIRST;

    /** Lenient parse for a query parameter, so a bad value is a 400 rather than a 500. */
    public static SortOrder parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown sort order '" + value + "'. Use NEWEST_FIRST or OLDEST_FIRST.");
        }
    }
}

package com.baysansoft.mqmanager.messaging.model;

/**
 * How to look through a destination when a plain "first N" browse is not enough.
 *
 * <p>Exists for the case a topic holds hundreds of thousands of records and the one wanted is nowhere
 * near the start. Rather than paging blindly, the caller can hand the broker a substring to match and/or
 * a point in time to start from, and the provider scans server-side.
 *
 * @param contains      case-insensitive substring the record body must contain, or null/blank for "any"
 * @param sinceEpochMs  start reading at the first record at or after this instant (Kafka seeks to it by
 *                      timestamp), or null to start at the beginning of the topic
 * @param caseSensitive match {@code contains} exactly rather than case-insensitively
 */
public record MessageQuery(String contains, Long sinceEpochMs, boolean caseSensitive) {

    public static final MessageQuery NONE = new MessageQuery(null, null, false);

    /** True when this asks for more than a plain browse — a text match or a time-based start point. */
    public boolean isSearch() {
        return (contains != null && !contains.isBlank()) || sinceEpochMs != null;
    }

    /** The needle to compare against, pre-lowercased when the match is case-insensitive; null for "any". */
    public String needle() {
        if (contains == null || contains.isBlank()) {
            return null;
        }
        return caseSensitive ? contains : contains.toLowerCase();
    }
}

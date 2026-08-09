package com.baysansoft.mqmanager.messaging.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.baysansoft.mqmanager.domain.DestinationKind;

/**
 * The filtering, ordering and capping every provider's listing goes through, written once.
 *
 * <p>Sorting happens <em>before</em> capping on purpose: that makes truncation deterministic across
 * calls and makes "the first N by name" a statement worth printing, rather than "whichever N the
 * broker happened to mention first".
 */
public final class DestinationListings {

    /** Case-insensitive first, then natural, so ties between "Orders" and "orders" are stable. */
    private static final Comparator<DestinationEntry> BY_NAME =
            Comparator.comparing(DestinationEntry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DestinationEntry::name);

    private DestinationListings() {
    }

    /**
     * Applies the query and the cap, then builds the listing.
     *
     * @param source plain-language name of the mechanism, carried into the response
     * @param note   provider-specific caveat, or null
     * @return {@link DestinationListing#complete} when everything fit, {@link DestinationListing#capped}
     *         when the limit cut it
     */
    public static DestinationListing finish(List<DestinationEntry> found, DestinationKind kind,
            String prefix, int limit, String source, String note) {
        List<DestinationEntry> matching = found.stream()
                .filter(entry -> kind == null || entry.kind() == kind)
                .filter(entry -> matchesPrefix(entry.name(), prefix))
                .sorted(BY_NAME)
                .toList();

        if (matching.size() > limit) {
            return DestinationListing.capped(matching.subList(0, limit), limit, source, note);
        }
        return DestinationListing.complete(matching, limit, source, note);
    }

    /**
     * A prefix, not a substring — see {@link DestinationQuery#prefix()}. Case-insensitive, because a
     * user typing {@code dev.} to find {@code DEV.QUEUE.1} is asking the obvious question.
     */
    public static boolean matchesPrefix(String name, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        return name != null
                && name.toLowerCase(Locale.ROOT).startsWith(prefix.trim().toLowerCase(Locale.ROOT));
    }
}

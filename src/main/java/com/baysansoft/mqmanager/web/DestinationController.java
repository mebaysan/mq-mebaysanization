package com.baysansoft.mqmanager.web;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.messaging.MessagingOperations;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.service.ConnectionProfileService;
import com.baysansoft.mqmanager.web.dto.DestinationResponses.DestinationListResponse;

/**
 * What destinations a saved connection's broker has.
 *
 * <p>Deliberately not under {@code /queue}: a listing is not an operation on one queue. As everywhere
 * else here, the one parameter that can contain a dot ({@code prefix}) travels as a query parameter,
 * so {@code SpaResourceConfig}'s static-file fallback never sees a dotted path segment.
 *
 * <p>A broker that <em>refuses</em> to be listed is a 200 carrying {@code availability=UNAVAILABLE},
 * not an error status: the caller asked what is on the broker, and "I am not permitted to tell you" is
 * an answer. Only an unreachable broker is an error. This mirrors {@code POST /test}, where a failed
 * connection test is likewise a successful HTTP call.
 */
@RestController
@RequestMapping("/api/connections/{id}/destinations")
public class DestinationController {

    private final ConnectionProfileService profiles;
    private final MessagingOperations messaging;

    public DestinationController(ConnectionProfileService profiles, MessagingOperations messaging) {
        this.profiles = profiles;
        this.messaging = messaging;
    }

    /**
     * @param kind   QUEUE or TOPIC, or absent for both. An unknown value is a 400 naming the valid
     *               ones, exactly as an unknown log level is
     * @param prefix a name prefix, not a substring. IBM MQ pushes it down to the queue manager; the
     *               others apply it after the fact. Substring search belongs in the UI, over what came
     *               back, and is labelled as such
     * @param limit  0 for the configured default; clamped against {@code mqmanager.destinations.max-limit}
     */
    @GetMapping
    public DestinationListResponse list(@PathVariable Long id,
                                        @RequestParam(required = false) String kind,
                                        @RequestParam(required = false) String prefix,
                                        @RequestParam(required = false, defaultValue = "0") int limit) {
        DestinationQuery query = new DestinationQuery(
                StringUtils.hasText(kind) ? DestinationKind.parse(kind) : null,
                StringUtils.hasText(prefix) ? prefix.trim() : null,
                limit);
        return DestinationListResponse.from(messaging.listDestinations(profiles.require(id), query));
    }
}

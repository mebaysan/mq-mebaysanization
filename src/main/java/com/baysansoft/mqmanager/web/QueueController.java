package com.baysansoft.mqmanager.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.MessagingOperations;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.MessageType;
import com.baysansoft.mqmanager.messaging.model.OutboundMessage;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.service.ConnectionProfileService;
import com.baysansoft.mqmanager.web.dto.QueueResponses.DeleteMessageResponse;
import com.baysansoft.mqmanager.web.dto.QueueResponses.DepthResponse;
import com.baysansoft.mqmanager.web.dto.QueueResponses.PurgeResponse;
import com.baysansoft.mqmanager.web.dto.QueueResponses.SendMessageResponse;
import com.baysansoft.mqmanager.web.dto.SendMessageRequest;

import jakarta.validation.Valid;

/**
 * Queue operations for a saved connection.
 *
 * <p>The queue name is always a query parameter, never a path segment. Both IBM MQ and the Apache brokers
 * allow dots in queue names ({@code DEV.QUEUE.1} is entirely ordinary), and a dotted path segment would
 * be treated as a static file request by the SPA fallback.
 */
@RestController
@RequestMapping("/api/connections/{id}/queue")
public class QueueController {

    private final ConnectionProfileService profiles;
    private final MessagingOperations messaging;

    public QueueController(ConnectionProfileService profiles, MessagingOperations messaging) {
        this.profiles = profiles;
        this.messaging = messaging;
    }

    @GetMapping("/messages")
    public BrowseResult browse(@PathVariable Long id,
                               @RequestParam String queueName,
                               @RequestParam(required = false, defaultValue = "0") int limit) {
        return messaging.browse(profile(id), requireQueueName(queueName), limit);
    }

    /** Full body and all headers for one message, for the expanded row. Non-destructive. */
    @GetMapping("/messages/one")
    public QueueMessageView browseOne(@PathVariable Long id,
                                      @RequestParam String queueName,
                                      @RequestParam String messageId) {
        return messaging.browseOne(profile(id), requireQueueName(queueName), messageId);
    }

    @PostMapping("/messages")
    public ResponseEntity<SendMessageResponse> send(@PathVariable Long id,
                                                    @RequestParam String queueName,
                                                    @Valid @RequestBody SendMessageRequest request) {
        // parseOptional, not parse: an omitted message type must stay exactly what it has always been —
        // a text message on the JMS providers, and nothing at all to object to on Kafka.
        OutboundMessage message = new OutboundMessage(request.payload(), request.properties(),
                request.key(), MessageType.parseOptional(request.messageType()));
        String messageId = messaging.send(profile(id), requireQueueName(queueName), message);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SendMessageResponse(messageId));
    }

    /**
     * "Not on the queue" is a 200 with {@code deleted:false}, not a 404 — the caller asked us to make
     * sure a message is gone, and it is. Cases where deletion could not be attempted reliably come back
     * as 409 from the messaging layer.
     */
    @DeleteMapping("/messages")
    public DeleteMessageResponse delete(@PathVariable Long id,
                                        @RequestParam String queueName,
                                        @RequestParam String messageId) {
        DeleteOutcome outcome =
                messaging.deleteMessageDetailed(profile(id), requireQueueName(queueName), messageId);
        return outcome == DeleteOutcome.DELETED
                ? DeleteMessageResponse.wasDeleted()
                : DeleteMessageResponse.wasNotFound();
    }

    @DeleteMapping("/purge")
    public PurgeResponse purge(@PathVariable Long id, @RequestParam String queueName) {
        return PurgeResponse.from(messaging.purgeDetailed(profile(id), requireQueueName(queueName)));
    }

    @GetMapping("/depth")
    public DepthResponse depth(@PathVariable Long id, @RequestParam String queueName) {
        return DepthResponse.from(messaging.depthDetailed(profile(id), requireQueueName(queueName)));
    }

    private ConnectionProfile profile(Long id) {
        return profiles.require(id);
    }

    private static String requireQueueName(String queueName) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("A queue name is required.");
        }
        return queueName.trim();
    }
}

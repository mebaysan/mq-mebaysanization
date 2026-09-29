package com.baysansoft.mqmanager.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A message-send request this instance composed for a destination, remembered so it can be sent again
 * without retyping the body, headers and options.
 *
 * <p>Server-side rather than in the browser, for the same reasons as {@link SavedDestination}: the tool
 * is often run on a machine other than the one it is read from, connections already live here, and a
 * second store in local storage would fork the state model.
 *
 * <p>Two kinds of row share this table, told apart by {@link #label}:
 * <ul>
 *   <li><strong>History</strong> — {@code label == null}. Written automatically on every successful send
 *       and trimmed back to a per-destination cap, oldest first. This is what "show the last request"
 *       reads.</li>
 *   <li><strong>Named</strong> — {@code label != null}. Saved deliberately by the user under a name, and
 *       never evicted by the cap, exactly as a pinned destination is never evicted.</li>
 * </ul>
 *
 * <p>This is a bookmark, not an audit trail. There are no users in this build, so there is nothing to
 * record about <em>who</em> composed it, and no attempt is made to prove a stored request was ever the
 * one actually sent — it is only what to prefill the composer with next time.
 */
@Entity
@Table(name = "saved_request")
public class SavedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A plain column rather than a {@code @ManyToOne}, for the same reasons as {@link SavedDestination}:
     * this application navigates no entity graph. The database enforces the reference and cascades the
     * delete; {@code V4} declares both.
     */
    @Column(name = "connection_profile_id", nullable = false)
    private Long connectionProfileId;

    /**
     * Case-sensitive, like {@link SavedDestination#getName()}: destination names are case-sensitive on
     * all four providers, so a request saved against {@code ORDERS} belongs to a different destination
     * than one saved against {@code orders}.
     */
    @Column(name = "destination_name", nullable = false, length = 512)
    private String destinationName;

    /**
     * The user's name for a saved request, or null for an automatic history entry. Uniqueness within a
     * destination is enforced by the service (upsert by label), not the schema — the application is the
     * only writer, and a null label must be allowed to repeat.
     */
    @Column(length = 120)
    private String label;

    /** The message body, exactly as it was composed. Large, but a single send is not an audit log. */
    @Column(nullable = false, length = 1_048_576)
    private String payload;

    /**
     * The custom properties/headers as a JSON object string, so one column round-trips a whole map
     * without an element-collection table this feature does not otherwise need. Never null: an empty map
     * is stored as {@code "{}"}.
     */
    @Column(name = "properties_json", nullable = false, length = 65_536)
    private String propertiesJson;

    /** Kafka record key, or null on a provider without one. */
    @Column(name = "message_key", length = 1_024)
    private String messageKey;

    /** JMS body type (TEXT/BYTES), or null on a provider without one. Stored as the name, like elsewhere. */
    @Column(name = "message_type", length = 16)
    private String messageType;

    /** IBM MQ target client (JMS/MQ), or null on a provider without one. */
    @Column(name = "target_client", length = 16)
    private String targetClient;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** True for a deliberately named request, false for an automatic history entry. */
    public boolean isNamed() {
        return label != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConnectionProfileId() {
        return connectionProfileId;
    }

    public void setConnectionProfileId(Long connectionProfileId) {
        this.connectionProfileId = connectionProfileId;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void setDestinationName(String destinationName) {
        this.destinationName = destinationName;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getPropertiesJson() {
        return propertiesJson;
    }

    public void setPropertiesJson(String propertiesJson) {
        this.propertiesJson = propertiesJson;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getTargetClient() {
        return targetClient;
    }

    public void setTargetClient(String targetClient) {
        this.targetClient = targetClient;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "SavedRequest{id=" + id + ", connectionProfileId=" + connectionProfileId
                + ", destinationName='" + destinationName + "', label=" + label + "}";
    }
}

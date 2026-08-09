package com.baysansoft.mqmanager.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A destination this instance has opened, remembered so it can be opened again without retyping.
 *
 * <p>Server-side rather than in the browser, and deliberately: connections already live here, the tool
 * is often run on a machine other than the one it is read from, and a second store in local storage
 * would fork the state model permanently for one feature.
 *
 * <p>This is a bookmark, not an audit trail. There are no users in this build, so there is nothing to
 * record about <em>who</em> opened it — a "last opened by" column could only ever be a lie.
 */
@Entity
@Table(name = "saved_destination",
        uniqueConstraints = @UniqueConstraint(name = "uk_saved_destination_connection_name",
                columnNames = {"connection_profile_id", "name"}))
public class SavedDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A plain column rather than a {@code @ManyToOne}.
     *
     * <p>This application has no entity graph at all, and a lazy association would buy a compile-time
     * link it never navigates in exchange for proxies, open-in-view questions and a second way to load
     * a profile. The database enforces the reference and cascades the delete; {@code V3} declares both.
     */
    @Column(name = "connection_profile_id", nullable = false)
    private Long connectionProfileId;

    /**
     * Case-sensitive, unlike a connection profile's name: queue and topic names are case-sensitive on
     * all four providers, so {@code ORDERS} and {@code orders} are two different destinations.
     */
    @Column(nullable = false, length = 512)
    private String name;

    /** STRING plus an explicit VARCHAR, for the same reasons as {@link ConnectionProfile}'s provider. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private DestinationKind kind;

    /** Pinned rows are never evicted by the retention cap, and sort above the rest. */
    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "open_count", nullable = false)
    private long openCount;

    @Column(name = "last_opened_at", nullable = false)
    private Instant lastOpenedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastOpenedAt == null) {
            lastOpenedAt = now;
        }
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DestinationKind getKind() {
        return kind;
    }

    public void setKind(DestinationKind kind) {
        this.kind = kind;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public long getOpenCount() {
        return openCount;
    }

    public void setOpenCount(long openCount) {
        this.openCount = openCount;
    }

    public Instant getLastOpenedAt() {
        return lastOpenedAt;
    }

    public void setLastOpenedAt(Instant lastOpenedAt) {
        this.lastOpenedAt = lastOpenedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "SavedDestination{id=" + id + ", connectionProfileId=" + connectionProfileId
                + ", name='" + name + "', kind=" + kind + ", pinned=" + pinned + "}";
    }
}

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A saved broker connection. The password is stored encrypted and is never serialized to a client:
 * responses are built from a separate DTO that has no password member at all.
 */
@Entity
@Table(name = "connection_profile",
       uniqueConstraints = @UniqueConstraint(name = "uk_connection_profile_name", columnNames = "name"))
public class ConnectionProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /**
     * STRING, never the JPA default of ORDINAL: reordering this enum in a later version would
     * silently repoint every stored profile at the wrong broker.
     *
     * <p>VARCHAR is forced explicitly. Left alone, Hibernate 6 emits a native H2
     * {@code enum ('ACTIVE_MQ','ARTEMIS','IBM_MQ')} column, which would turn "support one more broker"
     * into a schema migration.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private Provider provider;

    @Column(length = 255)
    private String host;

    private Integer port;

    @Column(length = 255)
    private String username;

    /** AES-256-GCM ciphertext produced by CryptoService, or null when no password is configured. */
    @Column(name = "password_cipher", length = 2048)
    private String passwordCipher;

    /** ActiveMQ Classic / Artemis only. Used verbatim in place of {@code tcp://host:port}. */
    @Column(name = "broker_url_override", length = 1024)
    private String brokerUrlOverride;

    /** IBM MQ only. */
    /**
     * Kafka's comma-separated {@code host:port} bootstrap list. Kafka addresses a cluster, not a single
     * endpoint, so it uses this instead of host/port — and never both.
     */
    @Column(name = "bootstrap_servers", length = 1024)
    private String bootstrapServers;

    @Column(name = "queue_manager_name", length = 48)
    private String queueManagerName;

    /** IBM MQ only. */
    @Column(length = 64)
    private String channel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    public void setPasswordCipher(String passwordCipher) {
        this.passwordCipher = passwordCipher;
    }

    public String getBrokerUrlOverride() {
        return brokerUrlOverride;
    }

    public void setBrokerUrlOverride(String brokerUrlOverride) {
        this.brokerUrlOverride = brokerUrlOverride;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getQueueManagerName() {
        return queueManagerName;
    }

    public void setQueueManagerName(String queueManagerName) {
        this.queueManagerName = queueManagerName;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Deliberately hand-written: never let a credential reach a log line. */
    @Override
    public String toString() {
        return "ConnectionProfile{id=%d, name='%s', provider=%s, host='%s', port=%s}"
                .formatted(id, name, provider, host, port);
    }
}

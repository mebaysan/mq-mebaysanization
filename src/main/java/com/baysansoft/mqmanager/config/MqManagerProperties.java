package com.baysansoft.mqmanager.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything tunable, bound from {@code application.yml} with environment-variable overrides. */
@ConfigurationProperties("mqmanager")
public class MqManagerProperties {

    private Path dataDir = Path.of("./data");

    /** Base64 32-byte AES key. Blank means "generate one into the data directory". */
    private String encryptionKey = "";

    /** Opt-in only. Message bodies are never written to the log unless this is true. */
    private boolean logPayloads = false;

    private Browse browse = new Browse();
    private Purge purge = new Purge();
    private Depth depth = new Depth();
    private Delete delete = new Delete();
    private Kafka kafka = new Kafka();

    public static class Browse {
        private int defaultLimit = 100;
        private int maxLimit = 1000;
        /** Bodies longer than this are cut down in list responses; the single-message view returns more. */
        private int previewBytes = 4096;
        private int maxBodyBytes = 1_048_576;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }

        public int getPreviewBytes() {
            return previewBytes;
        }

        public void setPreviewBytes(int previewBytes) {
            this.previewBytes = previewBytes;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }
    }

    public static class Purge {
        private int maxMessages = 50_000;
        private Duration maxDuration = Duration.ofSeconds(30);
        /** Generous, to absorb connect / open / page-in warm-up before the first message arrives. */
        private Duration firstReceiveTimeout = Duration.ofSeconds(5);
        private Duration receiveTimeout = Duration.ofSeconds(1);

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        public Duration getMaxDuration() {
            return maxDuration;
        }

        public void setMaxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
        }

        public Duration getFirstReceiveTimeout() {
            return firstReceiveTimeout;
        }

        public void setFirstReceiveTimeout(Duration firstReceiveTimeout) {
            this.firstReceiveTimeout = firstReceiveTimeout;
        }

        public Duration getReceiveTimeout() {
            return receiveTimeout;
        }

        public void setReceiveTimeout(Duration receiveTimeout) {
            this.receiveTimeout = receiveTimeout;
        }
    }

    public static class Depth {
        /** Past this we report "N+", because a browse-derived count cannot honestly claim more. */
        private int ceiling = 10_000;

        public int getCeiling() {
            return ceiling;
        }

        public void setCeiling(int ceiling) {
            this.ceiling = ceiling;
        }
    }

    /**
     * Kafka has no shared connection to time out, so every bound is per-call. The defaults are far
     * more aggressive than Kafka's own (which run to minutes) because a human is waiting on the other
     * end of an HTTP request.
     */
    public static class Kafka {
        /** Applied to metadata, offset and admin calls, and to {@code request.timeout.ms}. */
        private Duration apiTimeout = Duration.ofSeconds(5);
        /** One {@code poll()}. Short, because the browse loop polls repeatedly until it has enough. */
        private Duration pollTimeout = Duration.ofSeconds(1);
        /** Overall wall-clock bound on a browse, however many polls it takes. */
        private Duration browseTimeout = Duration.ofSeconds(15);
        /** How long a client may block while shutting down before it is abandoned. */
        private Duration closeTimeout = Duration.ofSeconds(2);
        /** Sent as {@code client.id}; the Kafka analogue of IBM MQ's application tag. */
        private String clientId = "mq-manager";

        public Duration getApiTimeout() {
            return apiTimeout;
        }

        public void setApiTimeout(Duration apiTimeout) {
            this.apiTimeout = apiTimeout;
        }

        public Duration getPollTimeout() {
            return pollTimeout;
        }

        public void setPollTimeout(Duration pollTimeout) {
            this.pollTimeout = pollTimeout;
        }

        public Duration getBrowseTimeout() {
            return browseTimeout;
        }

        public void setBrowseTimeout(Duration browseTimeout) {
            this.browseTimeout = browseTimeout;
        }

        public Duration getCloseTimeout() {
            return closeTimeout;
        }

        public void setCloseTimeout(Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }

    public static class Delete {
        private Duration receiveTimeout = Duration.ofSeconds(5);
        /** Bounds the browse that distinguishes "not on the queue" from "cannot be reached". */
        private int disambiguationLimit = 5_000;

        public Duration getReceiveTimeout() {
            return receiveTimeout;
        }

        public void setReceiveTimeout(Duration receiveTimeout) {
            this.receiveTimeout = receiveTimeout;
        }

        public int getDisambiguationLimit() {
            return disambiguationLimit;
        }

        public void setDisambiguationLimit(int disambiguationLimit) {
            this.disambiguationLimit = disambiguationLimit;
        }
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public boolean isLogPayloads() {
        return logPayloads;
    }

    public void setLogPayloads(boolean logPayloads) {
        this.logPayloads = logPayloads;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Browse getBrowse() {
        return browse;
    }

    public void setBrowse(Browse browse) {
        this.browse = browse;
    }

    public Purge getPurge() {
        return purge;
    }

    public void setPurge(Purge purge) {
        this.purge = purge;
    }

    public Depth getDepth() {
        return depth;
    }

    public void setDepth(Depth depth) {
        this.depth = depth;
    }

    public Delete getDelete() {
        return delete;
    }

    public void setDelete(Delete delete) {
        this.delete = delete;
    }
}

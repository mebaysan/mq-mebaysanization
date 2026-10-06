package com.baysansoft.mqmanager.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.baysansoft.mqmanager.domain.Provider;

/** Everything tunable, bound from {@code application.yml} with environment-variable overrides. */
@ConfigurationProperties("mqmanager")
public class MqManagerProperties {

    private Path dataDir = Path.of("./data");

    /** Base64 32-byte AES key. Blank means "generate one into the data directory". */
    private String encryptionKey = "";

    /**
      * Permits message bodies to be written to the log, and so to the in-memory buffer behind the
      * Logs page. Even when true a body is only written where DEBUG is enabled for this application's
      * logger, so the level is the second half of the guard.
      *
      * <p>False here but {@code true} in {@code application.yml}: this is the value a directly
      * constructed instance gets, which is what tests use. The shipped default is the yml one.
      */
     private boolean logPayloads = false;

    /**
     * Open the default browser at the app's own URL once the server is up. Off here (and in
     * {@code application.yml}) so that {@code mvn spring-boot:run} and every test stay silent; the
     * desktop launchers (the Windows .exe / .bat and {@code package-windows.ps1}) turn it on with
     * {@code -Dmqmanager.open-browser=true}, which is the one context where a human just double-clicked
     * an icon and expects a window.
     */
    private boolean openBrowser = false;

    private Browse browse = new Browse();
    private Purge purge = new Purge();
    private Depth depth = new Depth();
    private Delete delete = new Delete();
    private Destinations destinations = new Destinations();
    private Requests requests = new Requests();
    private List<SeedConnection> seedConnections = new ArrayList<>();
    private Kafka kafka = new Kafka();
    private Logs logs = new Logs();

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
        /** Overall wall-clock bound on a content search, which scans far more records than a browse. */
        private Duration searchTimeout = Duration.ofSeconds(20);
        /** How many records a single content search will read before it stops and says how far it got. */
        private int searchScanLimit = 200_000;
        /** How long a client may block while shutting down before it is abandoned. */
        private Duration closeTimeout = Duration.ofSeconds(2);
        /** Sent as {@code client.id}; the Kafka analogue of IBM MQ's application tag. */
        private String clientId = "mq-mebaysanization";

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

        public Duration getSearchTimeout() {
            return searchTimeout;
        }

        public void setSearchTimeout(Duration searchTimeout) {
            this.searchTimeout = searchTimeout;
        }

        public int getSearchScanLimit() {
            return searchScanLimit;
        }

        public void setSearchScanLimit(int searchScanLimit) {
            this.searchScanLimit = searchScanLimit;
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

    /**
     * The in-memory log ring buffer behind the monitoring page. Bounded on every axis, because this
     * lives in the heap of the process it is reporting on: a runaway logger must not be able to turn
     * a diagnostic aid into the outage.
     */
    public static class Logs {
        /** Lines kept. At the default this is well under a megabyte of heap. */
        private int capacity = 2_000;
        /** A single very long line is truncated rather than allowed to dominate the buffer. */
        private int maxMessageChars = 4_000;
        /** Set to 0 to keep stack traces out of the buffer, and so off the unauthenticated page. */
        private int maxStackTraceChars = 8_000;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getMaxMessageChars() {
            return maxMessageChars;
        }

        public void setMaxMessageChars(int maxMessageChars) {
            this.maxMessageChars = maxMessageChars;
        }

        public int getMaxStackTraceChars() {
            return maxStackTraceChars;
        }

        public void setMaxStackTraceChars(int maxStackTraceChars) {
            this.maxStackTraceChars = maxStackTraceChars;
        }
    }

    /**
     * Listing what is on a broker. Every provider answers a different way and every one of those ways
     * can be switched off, so the bounds here are about not hanging an HTTP request while a broker
     * declines to reply.
     */
    public static class Destinations {
        private int defaultLimit = 500;
        private int maxLimit = 2_000;
        /** Overall wall-clock budget for one listing, on every provider. A human is waiting. */
        private Duration timeout = Duration.ofSeconds(5);
        /**
         * ActiveMQ Classic only. Advisory messages arrive asynchronously with no completion signal, so
         * the scan waits for the count to stop growing and gives up here.
         */
        private Duration advisorySettle = Duration.ofMillis(1_500);
        /** ActiveMQ Classic only. How long between two "has the count stopped growing?" checks. */
        private Duration advisoryQuietPeriod = Duration.ofMillis(250);
        /** Artemis only. Brokers may rename the management address. */
        private String managementAddress = "activemq.management";
        /** IBM MQ only. How long PCF waits for the command server's reply. */
        private Duration commandWait = Duration.ofSeconds(5);
        /** How many remembered destinations are kept per connection before the oldest are evicted. */
        private int savedPerConnection = 50;

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

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getAdvisorySettle() {
            return advisorySettle;
        }

        public void setAdvisorySettle(Duration advisorySettle) {
            this.advisorySettle = advisorySettle;
        }

        public Duration getAdvisoryQuietPeriod() {
            return advisoryQuietPeriod;
        }

        public void setAdvisoryQuietPeriod(Duration advisoryQuietPeriod) {
            this.advisoryQuietPeriod = advisoryQuietPeriod;
        }

        public String getManagementAddress() {
            return managementAddress;
        }

        public void setManagementAddress(String managementAddress) {
            this.managementAddress = managementAddress;
        }

        public Duration getCommandWait() {
            return commandWait;
        }

        public void setCommandWait(Duration commandWait) {
            this.commandWait = commandWait;
        }

        public int getSavedPerConnection() {
            return savedPerConnection;
        }

        public void setSavedPerConnection(int savedPerConnection) {
            this.savedPerConnection = savedPerConnection;
        }
    }

    public static class Requests {
        /**
         * How many automatic history entries are kept per destination before the oldest are dropped.
         * Named requests carry no such cap — they are kept until forgotten, like a pinned destination.
         */
        private int historyPerDestination = 10;

        public int getHistoryPerDestination() {
            return historyPerDestination;
        }

        public void setHistoryPerDestination(int historyPerDestination) {
            this.historyPerDestination = historyPerDestination;
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

    public boolean isOpenBrowser() {
        return openBrowser;
    }

    public void setOpenBrowser(boolean openBrowser) {
        this.openBrowser = openBrowser;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Logs getLogs() {
        return logs;
    }

    public void setLogs(Logs logs) {
        this.logs = logs;
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

    public Destinations getDestinations() {
        return destinations;
    }

    public void setDestinations(Destinations destinations) {
        this.destinations = destinations;
    }

    public Requests getRequests() {
        return requests;
    }

    public void setRequests(Requests requests) {
        this.requests = requests;
    }

    public List<SeedConnection> getSeedConnections() {
        return seedConnections;
    }

    public void setSeedConnections(List<SeedConnection> seedConnections) {
        this.seedConnections = seedConnections;
    }

    /**
     * A connection to create automatically on first start, if one with the same name does not already
     * exist. Bound from {@code mqmanager.seed-connections}, which is deliberately empty in the committed
     * {@code application.yml}: real broker addresses are site-specific and belong in a local, untracked
     * file, never in a public repository. See {@code seed-connections.yml} and its {@code .gitignore}
     * entry, and {@code config/ConnectionSeeder}.
     *
     * <p>A JavaBean rather than a record because the enclosing properties bind by setter, and a list of
     * records would mix binding styles. Only the fields a real seed needs carry values; the rest mirror
     * the create form so a JMS broker could be seeded too.
     */
    public static class SeedConnection {
        private String name;
        private Provider provider;
        private String host;
        private Integer port;
        private String username;
        private String brokerUrlOverride;
        private String bootstrapServers;
        private String queueManagerName;
        private String channel;

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
    }
}

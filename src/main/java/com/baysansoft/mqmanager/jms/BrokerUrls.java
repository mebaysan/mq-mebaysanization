package com.baysansoft.mqmanager.jms;

import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.domain.ConnectionProfile;

/** Builds broker URLs, and keeps credentials out of anything that might be logged. */
public final class BrokerUrls {

    /**
     * Kept well under the app-level operation timeout so a dead host surfaces as a clear error rather
     * than the OS default of up to ~75 seconds.
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private BrokerUrls() {
    }

    /**
     * ActiveMQ Classic. A user-supplied override is returned <em>verbatim</em>, with no parameters
     * appended: the whole point of the field is expressing something the generated form cannot, such as
     * {@code failover://(tcp://a,tcp://b)?maxReconnectAttempts=1}, and appending to that would corrupt it.
     */
    public static String activeMqClassic(ConnectionProfile profile) {
        if (StringUtils.hasText(profile.getBrokerUrlOverride())) {
            return profile.getBrokerUrlOverride().trim();
        }
        return "tcp://%s:%d?connectionTimeout=%d"
                .formatted(profile.getHost(), port(profile, 61616), CONNECT_TIMEOUT_MS);
    }

    /** Artemis uses a different parameter name for the same idea. */
    public static String artemis(ConnectionProfile profile) {
        if (StringUtils.hasText(profile.getBrokerUrlOverride())) {
            return profile.getBrokerUrlOverride().trim();
        }
        return "tcp://%s:%d?connect-timeout-millis=%d"
                .formatted(profile.getHost(), port(profile, 61616), CONNECT_TIMEOUT_MS);
    }

    /** A short description safe to put in a log line or an error message. Never includes credentials. */
    public static String describe(ConnectionProfile profile) {
        if (profile.getProvider() != null && profile.getProvider().usesBootstrapServers()) {
            // Kafka has no host/port to fall through to; without this every message would read "null:null".
            return sanitize(profile.getBootstrapServers());
        }
        if (StringUtils.hasText(profile.getBrokerUrlOverride())) {
            return sanitize(profile.getBrokerUrlOverride().trim());
        }
        if (profile.getProvider().requiresQueueManager()) {
            return "%s:%s (queue manager %s, channel %s)".formatted(profile.getHost(), profile.getPort(),
                    profile.getQueueManagerName(), profile.getChannel());
        }
        return "%s:%s".formatted(profile.getHost(), profile.getPort());
    }

    /** Strips any {@code user:password@} segment from a URL a user may have pasted in. */
    public static String sanitize(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("://[^/@]*:[^/@]*@", "://****:****@");
    }

    private static int port(ConnectionProfile profile, int fallback) {
        return profile.getPort() == null ? fallback : profile.getPort();
    }
}

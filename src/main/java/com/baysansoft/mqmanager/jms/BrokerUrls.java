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

    /**
     * Masks any {@code user:password@} segment in a URL a user may have pasted in.
     *
     * <p>LOAD-BEARING GREED. The password group is {@code [^/]*}, not {@code [^/@]*}, so it runs to the
     * <em>last</em> {@code @} in the authority. A password containing {@code @} is one users really do
     * paste and brokers really do accept; against the narrower class the match anchored on the
     * <em>first</em> {@code @} and left the tail of the password in the clear — in 502 bodies and, worse,
     * on the unauthenticated Logs page, for any <em>saved</em> profile. Excluding {@code /} is what stops
     * the group running past a path separator into the next nested URL, which is what keeps composite
     * forms such as {@code failover://(tcp://a:61616,tcp://b:61616)} intact. The username group excludes
     * {@code :} rather than {@code @} so a username like {@code user@domain} is still recognised.
     *
     * <p>The backstop matters more than the pattern. A password containing {@code /} cannot be told
     * apart from a path, so rather than guess, anything still carrying an {@code @} the mask did not put
     * there is withheld wholesale. A caller that loses a hostname from one error message has lost
     * nothing that matters; a caller that leaks a credential cannot take it back.
     */
    public static String sanitize(String url) {
        if (url == null) {
            return null;
        }
        String masked = url.replaceAll("://[^/:]*:[^/]*@", "://****:****@");
        if (masked.replace("****:****@", "").indexOf('@') >= 0) {
            return "(broker URL withheld: it carries credentials this code cannot safely mask)";
        }
        return masked;
    }

    private static int port(ConnectionProfile profile, int fallback) {
        return profile.getPort() == null ? fallback : profile.getPort();
    }
}

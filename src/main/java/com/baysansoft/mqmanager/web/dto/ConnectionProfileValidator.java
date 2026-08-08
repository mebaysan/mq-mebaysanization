package com.baysansoft.mqmanager.web.dto;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.domain.Provider.Capability;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces the rules the single flat form cannot express with field annotations alone. Every violation
 * is reported against the specific field so the UI can highlight it.
 *
 * <p>The three addressing styles are mutually exclusive on purpose. A row that carried both a host and
 * a bootstrap list would leave no way to tell which one the operator meant to be authoritative, so a
 * field that does not apply to the chosen provider is rejected rather than ignored.
 */
public class ConnectionProfileValidator
        implements ConstraintValidator<ValidConnectionProfile, ConnectionProfileRequest> {

    @Override
    public boolean isValid(ConnectionProfileRequest request, ConstraintValidatorContext context) {
        if (request == null || request.provider() == null) {
            return true; // @NotNull on provider reports this
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        Provider provider = request.provider();
        boolean hasHost = StringUtils.hasText(request.host());
        boolean hasHostAndPort = hasHost && request.port() != null;
        boolean hasOverride = StringUtils.hasText(request.brokerUrlOverride());
        boolean hasBootstrap = StringUtils.hasText(request.bootstrapServers());

        if (provider.usesBootstrapServers()) {
            // Kafka: a cluster is addressed by a list of seed brokers, not one endpoint.
            if (!hasBootstrap) {
                reject(context, "bootstrapServers", "is required for " + provider.displayName());
                valid = false;
            } else if (!isBootstrapList(request.bootstrapServers())) {
                reject(context, "bootstrapServers", "must be a comma-separated list of host:port "
                        + "entries, for example broker1:9092,broker2:9092");
                valid = false;
            }
            if (hasHost) {
                reject(context, "host", hostDoesNotApply(provider));
                valid = false;
            }
            if (request.port() != null) {
                reject(context, "port", hostDoesNotApply(provider));
                valid = false;
            }
        } else if (provider.requiresQueueManager()) {
            if (!StringUtils.hasText(request.queueManagerName())) {
                reject(context, "queueManagerName", "is required for " + provider.displayName());
                valid = false;
            }
            if (!StringUtils.hasText(request.channel())) {
                reject(context, "channel", "is required for " + provider.displayName());
                valid = false;
            }
            if (!hasHostAndPort) {
                reject(context, "host", "host and port are required for " + provider.displayName());
                valid = false;
            }
        } else {
            // ActiveMQ Classic / Artemis: either host+port, or a raw broker URL used verbatim.
            if (!hasHostAndPort && !hasOverride) {
                reject(context, "host",
                        "either host and port, or a broker URL override, is required");
                valid = false;
            }
        }

        if (!provider.supportsBrokerUrlOverride() && hasOverride) {
            reject(context, "brokerUrlOverride",
                    "is not supported for " + provider.displayName() + " — use "
                            + (provider.usesBootstrapServers()
                                    ? "bootstrap servers instead"
                                    : "host, port, channel and queue manager instead"));
            valid = false;
        }

        if (!provider.usesBootstrapServers() && hasBootstrap) {
            reject(context, "bootstrapServers", onlyAppliesTo(Capability.USES_BOOTSTRAP_SERVERS));
            valid = false;
        }

        if (!provider.requiresQueueManager()) {
            if (StringUtils.hasText(request.queueManagerName())) {
                reject(context, "queueManagerName", onlyAppliesTo(Capability.REQUIRES_QUEUE_MANAGER));
                valid = false;
            }
            if (StringUtils.hasText(request.channel())) {
                reject(context, "channel", onlyAppliesTo(Capability.REQUIRES_QUEUE_MANAGER));
                valid = false;
            }
        }

        return valid;
    }

    private static void reject(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }

    private static String hostDoesNotApply(Provider provider) {
        return "does not apply to " + provider.displayName() + " — use bootstrap servers instead";
    }

    /**
     * Derives the message from the capability rather than hard-coding a broker name, so adding a
     * provider cannot leave the copy quietly wrong.
     */
    private static String onlyAppliesTo(Capability capability) {
        return "only applies to " + Stream.of(Provider.values())
                .filter(provider -> provider.has(capability))
                .map(Provider::displayName)
                .collect(Collectors.joining(" and "));
    }

    /**
     * A shape check, not a reachability check — a typo like a missing port is worth catching before a
     * connection attempt turns it into a confusing timeout. Accepts bracketed IPv6 literals.
     */
    private static boolean isBootstrapList(String value) {
        String[] entries = value.split(",", -1);
        if (entries.length == 0) {
            return false;
        }
        return Arrays.stream(entries).allMatch(ConnectionProfileValidator::isHostAndPort);
    }

    private static boolean isHostAndPort(String entry) {
        String trimmed = entry.trim();
        int separator = trimmed.startsWith("[")
                ? trimmed.indexOf("]:") + 1
                : trimmed.lastIndexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            return false;
        }
        try {
            int port = Integer.parseInt(trimmed.substring(separator + 1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

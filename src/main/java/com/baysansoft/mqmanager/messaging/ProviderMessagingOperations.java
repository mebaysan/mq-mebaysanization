package com.baysansoft.mqmanager.messaging;

import java.util.Set;

import com.baysansoft.mqmanager.domain.Provider;

/**
 * A {@link MessagingOperations} implementation together with the providers it speaks for.
 *
 * <p>{@link MessagingOperationsRouter} collects every bean of this type and dispatches on the profile's
 * provider. The router itself deliberately implements only {@code MessagingOperations}: if it also
 * implemented this interface, Spring would inject it into its own constructor argument.
 */
public interface ProviderMessagingOperations extends MessagingOperations {

    /** The providers this implementation handles. Must not overlap with any other implementation's. */
    Set<Provider> providers();
}

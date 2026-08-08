package com.baysansoft.mqmanager.messaging.model;

/**
 * Result of deleting one message by id.
 *
 * <p>"Could not be reached" is deliberately not a value here — it is raised as an error, because
 * reporting it alongside NOT_FOUND would let the UI tell a user a message is gone when it is still on
 * the queue.
 */
public enum DeleteOutcome {
    /** The message was consumed and is gone. */
    DELETED,
    /** The message is provably not on the queue: already consumed, expired, or a wrong id. */
    NOT_FOUND
}

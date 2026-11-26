package com.jazzlogs.backend.agent.limit;

import java.time.Duration;
import java.time.Instant;

import lombok.Getter;

/** A message refused before reaching the model, because a limit is spent until {@code resetsAt}. */
@Getter
public class MessageLimitExceededException extends RuntimeException {

    private final Scope scope;
    private final Instant resetsAt;
    private final Duration retryAfter;

    public MessageLimitExceededException(Scope scope, Instant resetsAt, Duration retryAfter) {
        super(scope.description + " message limit reached; it resets at " + resetsAt);
        this.scope = scope;
        this.resetsAt = resetsAt;
        this.retryAfter = retryAfter;
    }

    /** Which limit was hit: the user's own window, or every user's total for the day. */
    public enum Scope {
        USER("Per-user"),
        GLOBAL("Daily");

        private final String description;

        Scope(String description) {
            this.description = description;
        }
    }
}

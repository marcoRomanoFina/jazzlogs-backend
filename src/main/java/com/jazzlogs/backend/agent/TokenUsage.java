package com.jazzlogs.backend.agent;

/**
 * What one model call cost in tokens, as the provider reported it.
 *
 * @param input     tokens sent, cached ones included
 * @param cached    how many of {@code input} were served from the provider's prompt cache
 * @param output    tokens generated, reasoning included
 * @param reasoning how many of {@code output} were spent reasoning rather than answering
 */
public record TokenUsage(long input, long cached, long output, long reasoning) {

    /** For a call whose usage was not reported. */
    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, cached + other.cached, output + other.output, reasoning + other.reasoning);
    }
}

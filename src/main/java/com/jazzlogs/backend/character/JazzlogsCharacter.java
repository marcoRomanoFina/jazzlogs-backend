package com.jazzlogs.backend.character;

/**
 * The eight JazzLogs narrators — the single source of truth for "which
 * character": who signs a log ({@code TrackEditorial.byline}), who voices a
 * series or playlist, and who the user is talking to in a chat ({@code
 * Chat.narrator}). There is deliberately no unsigned/house value: everything
 * editorial is written by one of these eight.
 */
public enum JazzlogsCharacter {
    MARK,
    LAURA,
    ALICE,
    ADAM,
    JAMES,
    ALLIE,
    BOB,
    NATALIE
}

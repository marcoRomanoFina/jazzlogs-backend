package com.jazzlogs.backend.note.dto;

import java.time.Instant;
import java.util.UUID;

public record NoteDto(
    UUID id,
    UUID trackId,
    UUID userId,
    String userName,
    String text,
    Integer timestampSeconds,
    int likeCount,
    boolean likedByCurrentUser,
    Instant createdAt
) {
}

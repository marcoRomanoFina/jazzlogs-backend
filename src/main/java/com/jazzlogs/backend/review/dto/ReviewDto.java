package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.note.dto.NoteDto;

public record ReviewDto(
    UUID id,
    UUID albumId,
    UUID userId,
    String userName,
    BigDecimal rating,
    String text,
    int likeCount,
    boolean likedByCurrentUser,
    List<StandoutTrackDto> standoutTracks,
    // Full NoteDtos (not a lean summary) — the frontend renders/opens these
    // exactly like the per-track note feed, so they need the same shape:
    // trackId, text, likes, etc.
    List<NoteDto> notes,
    Instant createdAt,
    Instant updatedAt
) {
}

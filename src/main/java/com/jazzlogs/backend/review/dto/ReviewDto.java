package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    Instant createdAt,
    Instant updatedAt
) {
}

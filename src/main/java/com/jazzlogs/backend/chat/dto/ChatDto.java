package com.jazzlogs.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatDto(
    UUID id,
    String title,
    Instant createdAt,
    Instant updatedAt,
    Instant lastMessageAt
) {
}

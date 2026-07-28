package com.jazzlogs.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatExchangeDto(
    UUID id,
    UUID chatId,
    String userMessage,
    String finalResponse,
    Instant createdAt
) {
}

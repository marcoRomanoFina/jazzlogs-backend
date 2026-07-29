package com.jazzlogs.backend.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.chat.WinnerRef;

public record ChatExchangeDto(
    UUID id,
    UUID chatId,
    String userMessage,
    String finalResponse,
    List<WinnerRef> winners,
    Instant createdAt
) {
}

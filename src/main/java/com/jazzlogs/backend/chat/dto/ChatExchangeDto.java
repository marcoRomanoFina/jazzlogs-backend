package com.jazzlogs.backend.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.chat.WinnerRef;
/**
 * Projection of a {@link com.jazzlogs.backend.chat.ChatExchange} — one turn
 * of a chat, as returned by {@code GET /chats/{chatId}/exchanges}.
 *
 * @param id            the exchange's id
 * @param chatId        the chat this exchange belongs to
 * @param userMessage   what the user sent
 * @param finalResponse the agent's reply
 * @param winners       items the agent recommended in this exchange, if any
 * @param createdAt     when the exchange was recorded — drives list ordering
 */
public record ChatExchangeDto(
    UUID id,
    UUID chatId,
    String userMessage,
    String finalResponse,
    List<WinnerRef> winners,
    Instant createdAt
) {
}

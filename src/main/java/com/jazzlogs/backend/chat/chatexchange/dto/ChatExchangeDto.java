package com.jazzlogs.backend.chat.chatexchange.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Projection of a {@link com.jazzlogs.backend.chat.chatexchange.ChatExchange} — one turn
 * of a chat, as returned by {@code GET /chats/{chatId}/exchanges}.
 *
 * @param id            the exchange's id
 * @param chatId        the chat this exchange belongs to
 * @param userMessage   what the user sent
 * @param finalResponse the agent's reply
 * @param winners       items the agent recommended in this exchange, if any —
 *                      resolved fresh against the catalog, not the persisted
 *                      {@link com.jazzlogs.backend.chat.chatexchange.WinnerRef} snapshot
 * @param createdAt     when the exchange was recorded — drives list ordering
 */
public record ChatExchangeDto(
    UUID id,
    UUID chatId,
    String userMessage,
    String finalResponse,
    List<WinnerCard> winners,
    Instant createdAt
) {
}

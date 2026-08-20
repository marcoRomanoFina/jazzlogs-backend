package com.jazzlogs.backend.chat.chat.dto;

import java.time.Instant;
import java.util.UUID;
/**
 * Lightweight projection of a {@link Chat} — only what the chat list UI
 * needs, deliberately excluding exchanges and recommendation memory to
 * keep {@code GET /chats} cheap.
 *
 * @param id            the chat's id
 * @param title         auto-generated from the chat's first message
 * @param createdAt     when the chat was first created
 * @param updatedAt     last time any field on the chat itself changed
 * @param lastMessageAt when the most recent exchange was added — used for list ordering
 */
public record ChatDto(
    UUID id,
    String title,
    Instant createdAt,
    Instant updatedAt,
    Instant lastMessageAt
) {
}

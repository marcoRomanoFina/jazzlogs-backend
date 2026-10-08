package com.jazzlogs.backend.chat.chatexchange.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.character.JazzlogsCharacter;

/**
 * Body of {@code POST /chats} — a chat's first message. Same fields as
 * {@link SendMessageRequest} plus {@code narrator}, which only exists here:
 * a chat's narrator is chosen once, with its first message, and every later
 * {@code POST /chats/{chatId}/messages} reuses it.
 *
 * @param userMessage the user's first message
 * @param timezone    optional IANA zone id, see {@link SendMessageRequest}
 * @param narrator    who the user chose to talk to
 */
public record CreateChatRequest(@NotBlank String userMessage, String timezone, @NotNull JazzlogsCharacter narrator) {
}

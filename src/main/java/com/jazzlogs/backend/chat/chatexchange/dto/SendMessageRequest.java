package com.jazzlogs.backend.chat.chatexchange.dto;

import jakarta.validation.constraints.NotBlank;

// Body of both POST /chats (a chat's first message) and
// POST /chats/{chatId}/messages (any message after it).
// There's no finalResponse/winners field: the agent is what produces those,
// streamed back over SSE, never supplied by the caller (see
// AgentOrchestrator, ChatExchangeService).
// timezone is an optional IANA zone id (e.g. "America/Argentina/Buenos_Aires"),
// not persisted anywhere, only used to render ChatContextBuilder's RUNTIME
// CONTEXT; when absent the user's local time is simply unknown, never a failed request.
public record SendMessageRequest(@NotBlank String userMessage, String timezone) {
}

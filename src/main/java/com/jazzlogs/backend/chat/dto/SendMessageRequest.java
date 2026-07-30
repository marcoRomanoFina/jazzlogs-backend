package com.jazzlogs.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;

// Same body for both POST /chats (first message of a brand-new chat) and
// POST /chats/{chatId}/messages (any later message) — creating a chat is just
// sending its first message. There's no finalResponse/winners field: the
// agent is what produces those now, streamed back over SSE, never supplied
// by the caller (see AgentOrchestrator, ChatExchangeService).
// timezone is an optional IANA zone id (e.g. "America/Argentina/Buenos_Aires"),
// not persisted anywhere, only used to render ChatContextBuilder's RUNTIME
// CONTEXT; falls back to UTC when absent, never fails the request.
public record SendMessageRequest(@NotBlank String userMessage, String timezone) {
}

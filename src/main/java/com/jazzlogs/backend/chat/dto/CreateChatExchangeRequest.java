package com.jazzlogs.backend.chat.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.jazzlogs.backend.chat.WinnerRef;

// For an existing chat — chatId comes from the path, not the body.
// finalResponse still taken straight from the caller for now — no agent call
// yet (no tools, no Responses API, no streaming). winners is null/empty when
// nothing was recommended.
public record CreateChatExchangeRequest(
    @NotBlank String userMessage,
    @NotBlank String finalResponse,
    List<WinnerRef> winners
) {
}

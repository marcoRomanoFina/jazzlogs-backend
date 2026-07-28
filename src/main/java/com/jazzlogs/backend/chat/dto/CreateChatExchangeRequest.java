package com.jazzlogs.backend.chat.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;


public record CreateChatExchangeRequest(
    UUID chatId,
    boolean isNewChat,
    @NotBlank String userMessage,
    @NotBlank String finalResponse
) {
}

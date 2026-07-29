package com.jazzlogs.backend.chat.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.jazzlogs.backend.chat.WinnerRef;


public record CreateChatWithExchangeRequest(
    @NotBlank String userMessage,
    @NotBlank String finalResponse,
    List<WinnerRef> winners
) {
}

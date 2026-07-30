package com.jazzlogs.backend.syncfailure;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.chat.ChatRecommendationMemoryService;
import com.jazzlogs.backend.chat.WinnerRef;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class ChatRecommendationMemoryUpdatedSyncRetryHandler implements SyncRetryHandler {

    private final ChatRecommendationMemoryService chatRecommendationMemoryService;

    @Override
    public void retry(Map<String, Object> payload) {
        UUID chatId = UUID.fromString((String) payload.get("chatId"));
        List<WinnerRef> winners = ChatRecommendationMemoryService.winnersFromPayload(payload);
        String updatedSessionSummary = ChatRecommendationMemoryService.summaryFromPayload(payload);
        chatRecommendationMemoryService.recordMemoryUpdate(chatId, winners, updatedSessionSummary);
    }
}

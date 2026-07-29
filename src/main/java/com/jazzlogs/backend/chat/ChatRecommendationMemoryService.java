package com.jazzlogs.backend.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.syncfailure.SyncFailure;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.syncfailure.SyncFailureRepository;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// A performance layer over data that's in the worst case reconstructible by
// scanning chat_exchanges.winners — never the source of truth. syncWinners is
// the fire-and-forget entry point ChatService calls: off the request thread,
// and on failure it records a sync_failures row (same table/worker as the
// Neo4j sync, see ChatRecommendationMemoryUpdatedSyncRetryHandler) instead of
// propagating — a failure here must never take the exchange write down with
// it. recordWinners is the actual write, reused directly by the retry handler.
@Slf4j
@Service
@AllArgsConstructor
public class ChatRecommendationMemoryService {

    // High cap, truncated in-service (not SQL) — see ChatRecommendationMemory.appendWinners.
    private static final int WINNERS_HISTORY_CAP = 100;

    private final ChatRecommendationMemoryRepository chatRecommendationMemoryRepository;
    private final SyncFailureRepository syncFailureRepository;

    @Async
    public void syncWinners(UUID chatId, List<WinnerRef> winners) {
        try {
            recordWinners(chatId, winners);
        } catch (Exception ex) {
            log.warn("Failed to update chat_recommendation_memory for chat {}", chatId, ex);
            String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            syncFailureRepository.save(new SyncFailure(SyncFailureEntityType.CHAT_RECOMMENDATION_MEMORY_UPDATED, toPayload(chatId, winners), error));
        }
    }

    @Transactional
    public void recordWinners(UUID chatId, List<WinnerRef> winners) {
        ChatRecommendationMemory memory = chatRecommendationMemoryRepository.findByChatId(chatId)
            .orElseGet(() -> new ChatRecommendationMemory(chatId));
        memory.appendWinners(winners, WINNERS_HISTORY_CAP);
        chatRecommendationMemoryRepository.save(memory);
    }

    // Canonical-Strings payload contract (see ReviewService) — nested as a list
    // of maps since WinnerRef isn't primitive-only (primaryArtist can be null,
    // which Map.of() can't hold, hence LinkedHashMap here).
    public static Map<String, Object> toPayload(UUID chatId, List<WinnerRef> winners) {
        List<Map<String, Object>> winnerMaps = winners.stream().map(ChatRecommendationMemoryService::toMap).toList();
        return Map.of("chatId", chatId.toString(), "winners", winnerMaps);
    }

    @SuppressWarnings("unchecked")
    public static List<WinnerRef> fromPayload(Map<String, Object> payload) {
        List<Map<String, Object>> winnerMaps = (List<Map<String, Object>>) payload.get("winners");
        return winnerMaps.stream().map(ChatRecommendationMemoryService::toWinnerRef).toList();
    }

    private static Map<String, Object> toMap(WinnerRef winner) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", winner.type().name());
        map.put("id", winner.id().toString());
        map.put("name", winner.name());
        map.put("primaryArtist", winner.primaryArtist());
        return map;
    }

    private static WinnerRef toWinnerRef(Map<String, Object> map) {
        return new WinnerRef(
            CatalogItemType.valueOf((String) map.get("type")),
            UUID.fromString((String) map.get("id")),
            (String) map.get("name"),
            (String) map.get("primaryArtist")
        );
    }
}

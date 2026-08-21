package com.jazzlogs.backend.chat.chatexchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.syncfailure.SyncFailure;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.syncfailure.SyncFailureRepository;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// A performance layer over data that's in the worst case reconstructible by
// scanning chat_exchanges.winners — never the source of truth for winners.
// session_summary is the one exception (it's LLM-generated free text, not
// reconstructible from raw rows), but it shares this table/write-path anyway
// since it's produced at the exact same moment as winners, by the same
// final-answer turn. syncMemoryUpdate is the fire-and-forget entry
// point ChatExchangeService calls: off the request thread, and on failure
// it records a sync_failures row (same table/worker as the Neo4j sync, see
// ChatRecommendationMemoryUpdatedSyncRetryHandler) instead of propagating —
// a failure here must never take the exchange write down with it.
// recordMemoryUpdate is the actual write, reused directly by the retry handler.
@Slf4j
@Service
@AllArgsConstructor
public class ChatRecommendationMemoryService {

    // High cap, truncated in-service (not SQL) — see ChatRecommendationMemory.appendWinners.
    private static final int WINNERS_HISTORY_CAP = 100;

    private final ChatRecommendationMemoryRepository chatRecommendationMemoryRepository;
    private final SyncFailureRepository syncFailureRepository;

    @Async
    public void syncMemoryUpdate(UUID chatId, List<WinnerRef> winners, String updatedSessionSummary) {
        try {
            recordMemoryUpdate(chatId, winners, updatedSessionSummary);
        } catch (Exception ex) {
            log.warn("Failed to update chat_recommendation_memory for chat {}", chatId, ex);
            String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            Map<String, Object> payload = toPayload(chatId, winners, updatedSessionSummary);
            syncFailureRepository.save(new SyncFailure(SyncFailureEntityType.CHAT_RECOMMENDATION_MEMORY_UPDATED, payload, error));
        }
    }

    @Transactional
    public void recordMemoryUpdate(UUID chatId, List<WinnerRef> winners, String updatedSessionSummary) {
        ChatRecommendationMemory memory = chatRecommendationMemoryRepository.findByChatId(chatId)
            .orElseGet(() -> new ChatRecommendationMemory(chatId));

        if (winners != null && !winners.isEmpty()) {
            memory.appendWinners(winners, WINNERS_HISTORY_CAP);
        }
        if (updatedSessionSummary != null && !updatedSessionSummary.isBlank()) {
            memory.updateSessionSummary(updatedSessionSummary);
        }

        chatRecommendationMemoryRepository.save(memory);
    }

    // Canonical-Strings payload contract (see ReviewService) — winners nested
    // as a list of maps since WinnerRef isn't primitive-only.
    public static Map<String, Object> toPayload(UUID chatId, List<WinnerRef> winners, String updatedSessionSummary) {
        List<WinnerRef> safeWinners = winners == null ? List.of() : winners;
        List<Map<String, Object>> winnerMaps = safeWinners.stream().map(ChatRecommendationMemoryService::toMap).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chatId", chatId.toString());
        payload.put("winners", winnerMaps);
        payload.put("updatedSessionSummary", updatedSessionSummary);
        return payload;
    }

    @SuppressWarnings("unchecked")
    public static List<WinnerRef> winnersFromPayload(Map<String, Object> payload) {
        List<Map<String, Object>> winnerMaps = (List<Map<String, Object>>) payload.get("winners");
        return winnerMaps == null ? List.of() : winnerMaps.stream().map(ChatRecommendationMemoryService::toWinnerRef).toList();
    }

    public static String summaryFromPayload(Map<String, Object> payload) {
        return (String) payload.get("updatedSessionSummary");
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

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

/**
 * A performance layer over data that's in the worst case reconstructible by
 * scanning {@code chat_exchanges.winners} — never the source of truth for
 * winners. {@code session_summary} is the one exception (it's LLM-generated
 * free text, not reconstructible from raw rows), but it shares this
 * table/write-path anyway since it's produced at the exact same moment as
 * winners, by the same final-answer turn.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatRecommendationMemoryService {

    // High cap, truncated in-service (not SQL) — see ChatRecommendationMemory.appendWinners.
    private static final int WINNERS_HISTORY_CAP = 100;

    private final ChatRecommendationMemoryRepository chatRecommendationMemoryRepository;
    private final SyncFailureRepository syncFailureRepository;

    /**
     * The fire-and-forget entry point {@code ChatExchangeService} calls after
     * every successful exchange, off the request thread. On failure, records
     * a {@code sync_failures} row for the retry worker (see {@link
     * com.jazzlogs.backend.syncfailure.ChatRecommendationMemoryUpdatedSyncRetryHandler})
     * instead of propagating — a failure here must never take the exchange
     * write down with it.
     *
     * @param chatId               the chat whose memory to update
     * @param winners              this turn's recommended items, appended to the rolling history
     * @param updatedSessionSummary the model's updated summary, or null/blank to leave it unchanged
     */
    @Async
    public void syncMemoryUpdate(UUID chatId, List<WinnerReference> winners, String updatedSessionSummary) {
        try {
            recordMemoryUpdate(chatId, winners, updatedSessionSummary);
        } catch (Exception ex) {
            log.warn("Failed to update chat_recommendation_memory for chat {}", chatId, ex);
            String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            Map<String, Object> payload = toPayload(chatId, winners, updatedSessionSummary);
            syncFailureRepository.save(new SyncFailure(SyncFailureEntityType.CHAT_RECOMMENDATION_MEMORY_UPDATED, payload, error));
        }
    }

    /**
     * The actual write, reused directly by the retry handler: loads (or
     * creates) the chat's memory row, appends {@code winners} to its capped
     * history, and updates the session summary — either can be skipped
     * independently when there's nothing new to record for it.
     *
     * @param chatId               the chat whose memory to update
     * @param winners              this turn's recommended items; skipped if null/empty
     * @param updatedSessionSummary the model's updated summary; skipped if null/blank
     */
    @Transactional
    public void recordMemoryUpdate(UUID chatId, List<WinnerReference> winners, String updatedSessionSummary) {
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

    /**
     * Builds the {@code sync_failures} payload for a failed {@link #syncMemoryUpdate}
     * call — canonical-strings contract (see {@code ReviewService}), winners
     * nested as a list of maps since {@link WinnerReference} isn't primitive-only.
     */
    public static Map<String, Object> toPayload(UUID chatId, List<WinnerReference> winners, String updatedSessionSummary) {
        List<WinnerReference> safeWinners = winners == null ? List.of() : winners;
        List<Map<String, Object>> winnerMaps = safeWinners.stream().map(ChatRecommendationMemoryService::toMap).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chatId", chatId.toString());
        payload.put("winners", winnerMaps);
        payload.put("updatedSessionSummary", updatedSessionSummary);
        return payload;
    }

    /** The retry handler's counterpart to {@link #toPayload} — reads winners back out of a saved payload. */
    @SuppressWarnings("unchecked")
    public static List<WinnerReference> winnersFromPayload(Map<String, Object> payload) {
        List<Map<String, Object>> winnerMaps = (List<Map<String, Object>>) payload.get("winners");
        return winnerMaps == null ? List.of() : winnerMaps.stream().map(ChatRecommendationMemoryService::toWinnerReference).toList();
    }

    /** The retry handler's counterpart to {@link #toPayload} — reads the session summary back out of a saved payload. */
    public static String summaryFromPayload(Map<String, Object> payload) {
        return (String) payload.get("updatedSessionSummary");
    }

    /** One {@link WinnerReference} as a plain string-keyed map, for {@link #toPayload}. */
    private static Map<String, Object> toMap(WinnerReference winner) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", winner.type().name());
        map.put("id", winner.id().toString());
        map.put("name", winner.name());
        map.put("primaryArtist", winner.primaryArtist());
        return map;
    }

    /** The inverse of {@link #toMap}. */
    private static WinnerReference toWinnerReference(Map<String, Object> map) {
        return new WinnerReference(
            CatalogItemType.valueOf((String) map.get("type")),
            UUID.fromString((String) map.get("id")),
            (String) map.get("name"),
            (String) map.get("primaryArtist")
        );
    }
}

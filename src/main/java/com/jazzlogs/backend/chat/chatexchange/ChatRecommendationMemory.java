package com.jazzlogs.backend.chat.chatexchange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A chat's cross-exchange memory: the rolling recommendation history and
 * session summary that carry forward from turn to turn, one row per chat.
 * Kept as its own table rather than columns on {@code Chat} so the hot
 * {@code GET /chats} path never has to drag this JSONB along.
 */
@Entity
@Table(name = "chat_recommendation_memory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRecommendationMemory {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "chat_id", nullable = false, unique = true)
    private UUID chatId;

    /**
     * Full-but-light history of everything recommended in the session, capped
     * and truncated from the front (oldest first) in {@link #appendWinners} —
     * see {@code ChatRecommendationMemoryService.WINNERS_HISTORY_CAP}. Used
     * for "don't repeat a recommendation already shown this session".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "winners_history")
    private List<WinnerReference> winnersHistory = new ArrayList<>();

    // Free text, model-generated (AgentFinalAnswer.updatedSessionSummary),
    // updated exchange to exchange — not reconstructible from raw rows, see
    // ChatRecommendationMemoryService's class doc.
    @Column(name = "session_summary", columnDefinition = "TEXT")
    private String sessionSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Starts a brand-new, empty memory row for a chat that doesn't have one yet. */
    public ChatRecommendationMemory(UUID chatId) {
        this.chatId = chatId;
    }

    /** Replaces the session summary outright — the model always sends the full updated text, never a delta. */
    public void updateSessionSummary(String sessionSummary) {
        this.sessionSummary = sessionSummary;
    }

    /**
     * Appends this turn's winners to the history, then truncates from the
     * front (oldest first) down to {@code cap} — in-memory, not relying on
     * unbounded SQL growth.
     *
     * @param newWinners this turn's recommended items
     * @param cap        the maximum history size to keep afterward
     */
    public void appendWinners(List<WinnerReference> newWinners, int cap) {
        List<WinnerReference> combined = new ArrayList<>(winnersHistory);
        combined.addAll(newWinners);

        int excess = combined.size() - cap;
        this.winnersHistory = excess > 0 ? new ArrayList<>(combined.subList(excess, combined.size())) : combined;
    }

    /** Stamps both timestamps on first insert. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes {@code updatedAt} on every subsequent save. */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

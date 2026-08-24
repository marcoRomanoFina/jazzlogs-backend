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

    // High cap, truncated in-service (not SQL) — see appendWinners.
    private static final int WINNERS_HISTORY_CAP = 100;

    // Defends against a model that keeps growing the summary instead of
    // summarizing — see updateSessionSummary. ~2000 chars is generous for a
    // few sentences of user preferences/context.
    private static final int SESSION_SUMMARY_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "chat_id", nullable = false, unique = true)
    private UUID chatId;

    /**
     * Full-but-light history of everything recommended in the session, capped
     * and truncated from the front (oldest first) in {@link #appendWinners}.
     * Used for "don't repeat a recommendation already shown this session".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "winners_history")
    private List<WinnerReference> winnersHistory = new ArrayList<>();

    /** Model-generated free text ({@code AgentFinalAnswer.updatedSessionSummary}), replaced whole each exchange. */
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

    /** Replaces the session summary outright, keeping only the last {@link #SESSION_SUMMARY_MAX_LENGTH} characters. */
    public void updateSessionSummary(String sessionSummary) {
        this.sessionSummary = sessionSummary.length() > SESSION_SUMMARY_MAX_LENGTH
            ? sessionSummary.substring(sessionSummary.length() - SESSION_SUMMARY_MAX_LENGTH)
            : sessionSummary;
    }

    /**
     * Appends this turn's winners to the history, then truncates from the
     * front (oldest first) down to {@link #WINNERS_HISTORY_CAP} — in-memory,
     * not relying on unbounded SQL growth.
     *
     * @param newWinners this turn's recommended items
     */
    public void appendWinners(List<WinnerReference> newWinners) {
        List<WinnerReference> combined = new ArrayList<>(winnersHistory);
        combined.addAll(newWinners);

        int excess = combined.size() - WINNERS_HISTORY_CAP;
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

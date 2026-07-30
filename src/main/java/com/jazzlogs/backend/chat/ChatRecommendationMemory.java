package com.jazzlogs.backend.chat;

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

// 1:1 with Chat, kept as its own table (not columns on Chat) on purpose: Chat
// is the hot path for GET /chats and must not drag JSONB along, and the two
// are written by different services on different timing (ChatService touches
// chats, ChatRecommendationMemoryService touches this one after resolving an
// exchange). chatId is a plain column, not a @ManyToOne — the memory writer
// only ever has the id value, never needs to load Chat, and there's no
// bidirectional nav from Chat either (see Chat.java).
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

    // Full-but-light history of everything recommended in the session, capped
    // and truncated from the front (oldest first) in appendWinners — see the
    // cap constant on ChatRecommendationMemoryService. Used for "don't repeat
    // a recommendation already shown this session".
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "winners_history")
    private List<WinnerRef> winnersHistory = new ArrayList<>();

    // Free text, updated exchange to exchange. No generation logic yet — the
    // column is ready, nothing writes to it until the agent step exists.
    @Column(name = "session_summary", columnDefinition = "TEXT")
    private String sessionSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChatRecommendationMemory(UUID chatId) {
        this.chatId = chatId;
    }

    public void updateSessionSummary(String sessionSummary) {
        this.sessionSummary = sessionSummary;
    }

    public void appendWinners(List<WinnerRef> newWinners, int cap) {
        List<WinnerRef> combined = new ArrayList<>(winnersHistory);
        combined.addAll(newWinners);

        int excess = combined.size() - cap;
        this.winnersHistory = excess > 0 ? new ArrayList<>(combined.subList(excess, combined.size())) : combined;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

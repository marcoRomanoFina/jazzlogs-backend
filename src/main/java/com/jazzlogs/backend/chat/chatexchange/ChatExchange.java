package com.jazzlogs.backend.chat.chatexchange;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.jazzlogs.backend.chat.chat.Chat;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One user message / agent response pair within a {@link Chat} — a single
 * turn of the conversation, immutable once persisted (no updated_at, no
 * setters). {@code winners} records what the agent recommended in this
 * exchange, if anything; {@link ChatRecommendationMemory} separately tracks
 * everything recommended across the whole chat, to avoid repeating it.
 */
@Entity
@Table(name = "chat_exchanges", indexes = @Index(name = "idx_chat_exchanges_chat_id_created_at", columnList = "chat_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatExchange {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "final_response", nullable = false, columnDefinition = "TEXT")
    private String finalResponse;

    // Null/empty when this exchange didn't recommend anything.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "winners")
    private List<WinnerRef> winners;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ChatExchange(Chat chat, String userMessage, String finalResponse, List<WinnerRef> winners) {
        this.chat = chat;
        this.userMessage = userMessage;
        this.finalResponse = finalResponse;
        this.winners = winners;
    }

    public UUID getChatId() {
        return chat.getId();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

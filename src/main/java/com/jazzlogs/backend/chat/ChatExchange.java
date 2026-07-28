package com.jazzlogs.backend.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "chat_exchanges")
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ChatExchange(Chat chat, String userMessage, String finalResponse) {
        this.chat = chat;
        this.userMessage = userMessage;
        this.finalResponse = finalResponse;
    }

    public UUID getChatId() {
        return chat.getId();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

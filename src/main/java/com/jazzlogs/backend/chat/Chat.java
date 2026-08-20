package com.jazzlogs.backend.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.user.User;

@Entity
@Table(name = "chats", indexes = @Index(name = "idx_chats_user_id_last_message_at", columnList = "user_id, last_message_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chat {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    public Chat(User user, String title) {
        this.user = user;
        this.title = title;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public void recordExchangeAt(Instant exchangeCreatedAt) {
        this.lastMessageAt = exchangeCreatedAt;
    }

    public void updateTitle(String title) {
        this.title = title;
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

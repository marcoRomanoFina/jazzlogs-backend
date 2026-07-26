package com.jazzlogs.backend.syncfailure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "sync_failures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncFailure {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private SyncFailureEntityType entityType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncFailureStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    
    public SyncFailure(SyncFailureEntityType entityType, Map<String, Object> payload, String initialError) {
        this.entityType = entityType;
        this.payload = payload;
        this.attempts = 0;
        this.lastError = initialError;
        this.status = SyncFailureStatus.PENDING;
    }

    public void recordAttemptFailure(String error, int maxAttempts) {
        this.attempts++;
        this.lastAttemptAt = Instant.now();
        this.lastError = error;
        if (this.attempts >= maxAttempts) {
            this.status = SyncFailureStatus.DEAD;
        }
    }

    
    public void markResolved() {
        this.status = SyncFailureStatus.RESOLVED;
        this.lastAttemptAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

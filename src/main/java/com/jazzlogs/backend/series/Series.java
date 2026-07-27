package com.jazzlogs.backend.series;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Audio-series (podcast-style editorial content) — NOT a subclass of Editorial.
// Out of the Neo4j graph by design (no recommendation-agent signal yet).
@Entity
@Table(name = "series")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Series {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String title;

    private String dek;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeriesStatus status;

    // Denormalized, same contract as Playlist/Review/Note.likeCount — mutated
    // only via SeriesRepository's atomic increment/decrement UPDATE queries.
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Series(String title, String dek, String description, String coverImageUrl, SeriesStatus status) {
        this.title = title;
        this.dek = dek;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.status = status;
    }

    public void update(String title, String dek, String description, String coverImageUrl, SeriesStatus status) {
        this.title = title;
        this.dek = dek;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.status = status;
    }

    public boolean isPublished() {
        return status == SeriesStatus.PUBLISHED;
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

package com.jazzlogs.backend.playlist;

import java.time.Instant;
import java.util.UUID;

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

// Official/editorial playlists curated by JazzLogs — not user playlists 
@Entity
@Table(name = "playlists")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Playlist {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    private String tagline;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "spotify_url")
    private String spotifyUrl;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @Column(name = "track_count", nullable = false)
    private int trackCount;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Playlist(
        String slug,
        String title,
        String tagline,
        String description,
        String coverImageUrl,
        String spotifyUrl,
        boolean published
    ) {
        this.slug = slug;
        this.title = title;
        this.tagline = tagline;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.spotifyUrl = spotifyUrl;
        this.published = published;
    }

    public void update(
        String slug,
        String title,
        String tagline,
        String description,
        String coverImageUrl,
        String spotifyUrl,
        boolean published
    ) {
        this.slug = slug;
        this.title = title;
        this.tagline = tagline;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.spotifyUrl = spotifyUrl;
        this.published = published;
    }

    public void updateTrackStats(int trackCount, long durationMs) {
        this.trackCount = trackCount;
        this.durationMs = durationMs;
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

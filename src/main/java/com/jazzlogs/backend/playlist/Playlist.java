package com.jazzlogs.backend.playlist;

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

// Official/editorial playlists curated by JazzLogs — not user playlists 
@Entity
@Table(name = "playlists")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Playlist {

    @Id
    @GeneratedValue
    private UUID id;

    // Enforced at the DB level (uq_playlists_title, V29) — no two playlists
    // can share a title. PlaylistService checks it up front too, for a clean
    // 409 instead of surfacing the raw constraint violation.
    @Column(nullable = false, unique = true)
    private String title;

    private String tagline;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "spotify_url")
    private String spotifyUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaylistType type;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @Column(name = "track_count", nullable = false)
    private int trackCount;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
    
    @Column(nullable = false)
    private boolean featured;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Every new playlist starts as a draft (published defaults to false) —
    // not a constructor param, see PlaylistService.publish/unpublish.
    public Playlist(
        String title,
        String tagline,
        String description,
        String coverImageUrl,
        String spotifyUrl,
        PlaylistType type
    ) {
        this.title = title;
        this.tagline = tagline;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.spotifyUrl = spotifyUrl;
        this.type = type;
    }

    public void update(
        String title,
        String tagline,
        String description,
        String coverImageUrl,
        String spotifyUrl,
        PlaylistType type
    ) {
        this.title = title;
        this.tagline = tagline;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.spotifyUrl = spotifyUrl;
        this.type = type;
    }

    public void updateTrackStats(int trackCount, long durationMs) {
        this.trackCount = trackCount;
        this.durationMs = durationMs;
    }

    // Separate from update(...) — set via its own upload endpoint
    // (PlaylistService.setCoverImage), not the metadata upsert.
    public void updateCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    /** See {@code PlaylistService.publish}. */
    public void publish() {
        this.published = true;
    }

    /** See {@code PlaylistService.unpublish}. */
    public void unpublish() {
        this.published = false;
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

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

    // Enforced at the DB level (uq_series_title, V39) — no two series can
    // share a title. SeriesService checks it up front too, for a clean 409
    // instead of surfacing the raw constraint violation. SeriesService.getOnboardingSeries
    // relies on this to look a series up by title safely.
    @Column(nullable = false, unique = true)
    private String title;

    private String dek;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    // Three more, distinct from coverImageUrl (the catalogue/card thumbnail)
    // — used to lay out the series detail page. Same upload-only contract as
    // coverImageUrl: never set via update(...), only via their own endpoints.
    @Column(name = "principal_image_url")
    private String principalImageUrl;

    @Column(name = "banner_image_url")
    private String bannerImageUrl;

    @Column(name = "footer_image_url")
    private String footerImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeriesStatus status;

    // Which narrator reads this series — pure classification, no behavior
    // difference (same reasoning as Playlist.type).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeriesVoice voice;

    // Denormalized, same contract as Playlist/Review/Note.likeCount — mutated
    // only via SeriesRepository's atomic increment/decrement UPDATE queries.
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(nullable = false)
    private boolean featured;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Every new series starts as a draft (status defaults to DRAFT) — not a
    // constructor param, see SeriesService.publish/unpublish. coverImageUrl
    // isn't one either — it's only ever set via updateCoverImageUrl, after an
    // upload (see SeriesService.setCoverImage).
    public Series(String title, String dek, String description, SeriesVoice voice) {
        this.title = title;
        this.dek = dek;
        this.description = description;
        this.voice = voice;
        this.status = SeriesStatus.DRAFT;
    }

    public void update(String title, String dek, String description, SeriesVoice voice) {
        this.title = title;
        this.dek = dek;
        this.description = description;
        this.voice = voice;
    }

    // Separate from update(...) — set via its own upload endpoint
    // (SeriesService.setCoverImage), not the metadata upsert.
    public void updateCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public void updatePrincipalImageUrl(String principalImageUrl) {
        this.principalImageUrl = principalImageUrl;
    }

    public void updateBannerImageUrl(String bannerImageUrl) {
        this.bannerImageUrl = bannerImageUrl;
    }

    public void updateFooterImageUrl(String footerImageUrl) {
        this.footerImageUrl = footerImageUrl;
    }

    public boolean isPublished() {
        return status == SeriesStatus.PUBLISHED;
    }

    /** See {@code SeriesService.publish}. */
    public void publish() {
        this.status = SeriesStatus.PUBLISHED;
    }

    /** See {@code SeriesService.unpublish}. */
    public void unpublish() {
        this.status = SeriesStatus.DRAFT;
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

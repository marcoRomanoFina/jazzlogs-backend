package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.track.Track;

/**
 * A track's own editorial — flat (no shared base table): tracks are the only
 * kind of editorial content JazzLogs carries, so there's no other subtype to
 * share columns with.
 */
@Entity
@Table(name = "track_editorials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackEditorial {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false, unique = true)
    private Track track;

    @Column(nullable = false)
    private String title;

    // Admin-set — the number JazzLogs itself refers to this log by, updated
    // alongside title/dek/byline via the same upsert.
    @Column(name = "log_number", nullable = false)
    private String logNumber;

    @Column(columnDefinition = "TEXT")
    private String dek;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EditorialByline byline;

    // Five images for this editorial's own page layout — upload-only, never
    // set via update(...), only via their own endpoints (see
    // EditorialService.setTrackEditorial*Image).
    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "principal_image_url")
    private String principalImageUrl;

    @Column(name = "secondary_image_url")
    private String secondaryImageUrl;

    @Column(name = "banner_image_url")
    private String bannerImageUrl;

    @Column(name = "footer_image_url")
    private String footerImageUrl;

    @OneToMany(mappedBy = "trackEditorial", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<EditorialBlock> blocks = new ArrayList<>();

    // Denormalized on purpose — mutated only via TrackEditorialRepository's
    // atomic increment/decrement UPDATE queries (see LikeService), never
    // read-modify-saved in Java, to avoid lost updates under concurrent likes.
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public TrackEditorial(Track track) {
        this.track = track;
    }

    public void update(String title, String dek, EditorialByline byline, String logNumber) {
        this.title = title;
        this.dek = dek;
        this.byline = byline;
        this.logNumber = logNumber;
    }

    public void updateCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public void updatePrincipalImageUrl(String principalImageUrl) {
        this.principalImageUrl = principalImageUrl;
    }

    public void updateSecondaryImageUrl(String secondaryImageUrl) {
        this.secondaryImageUrl = secondaryImageUrl;
    }

    public void updateBannerImageUrl(String bannerImageUrl) {
        this.bannerImageUrl = bannerImageUrl;
    }

    public void updateFooterImageUrl(String footerImageUrl) {
        this.footerImageUrl = footerImageUrl;
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

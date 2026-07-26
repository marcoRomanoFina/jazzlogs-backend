package com.jazzlogs.backend.trackrating;

import java.math.BigDecimal;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.user.User;

// One rating per (user, track) — DB-enforced via uq_track_ratings_user_track.
// TrackRatingService.upsertRating is the only writer: update in place if one
// already exists, insert otherwise. No text, no like_count — for text use
// Note (separate entity, multiple entries allowed); this isn't audience-facing
// content, just a private numeric verdict.
@Entity
@Table(name = "track_ratings", uniqueConstraints = @UniqueConstraint(name = "uq_track_ratings_user_track", columnNames = {"user_id", "track_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackRating {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false)
    private Track track;

    // BigDecimal, not double/float — NUMERIC(2,1) needs exact decimal
    // arithmetic, not floating-point approximation.
    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TrackRating(User user, Track track, BigDecimal rating) {
        this.user = user;
        this.track = track;
        this.rating = rating;
    }

    public void update(BigDecimal rating) {
        this.rating = rating;
    }

    public UUID getUserId() {
        return user.getId();
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

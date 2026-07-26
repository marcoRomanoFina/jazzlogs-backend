package com.jazzlogs.backend.review;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.user.User;

// One review per (user, album) — DB-enforced via uq_reviews_user_album.
// ReviewService.upsertReview is the only writer: update in place if one
// already exists, insert otherwise.
@Entity
@Table(name = "reviews", uniqueConstraints = @UniqueConstraint(name = "uq_reviews_user_album", columnNames = {"user_id", "album_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    // BigDecimal, not double/float — NUMERIC(2,1) needs exact decimal
    // arithmetic, not floating-point approximation.
    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(columnDefinition = "TEXT")
    private String text;

    // Denormalized, same contract as Editorial/Note.likeCount.
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @ManyToMany
    @JoinTable(
        name = "review_standout_tracks",
        joinColumns = @JoinColumn(name = "review_id"),
        inverseJoinColumns = @JoinColumn(name = "track_id")
    )
    private Set<Track> standoutTracks = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Review(User user, Album album, BigDecimal rating, String text) {
        this.user = user;
        this.album = album;
        this.rating = rating;
        this.text = text;
    }

    public void update(BigDecimal rating, String text) {
        this.rating = rating;
        this.text = text;
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

package com.jazzlogs.backend.note;

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

import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.user.User;

// A user can have several notes on the same track (different moments) — no
// uniqueness constraint on (user_id, track_id) on purpose.
@Entity
@Table(name = "notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Note {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false)
    private Track track;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    // Optional — a specific moment in the track this note is about.
    @Column(name = "timestamp_seconds")
    private Integer timestampSeconds;

    // Denormalized, same contract as Editorial.likeCount — mutated only via
    // NoteRepository's atomic increment/decrement UPDATE queries (see LikeService).
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Note(User user, Track track, String title, String text, Integer timestampSeconds) {
        this.user = user;
        this.track = track;
        this.title = title;
        this.text = text;
        this.timestampSeconds = timestampSeconds;
    }

    public UUID getUserId() {
        return user.getId();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

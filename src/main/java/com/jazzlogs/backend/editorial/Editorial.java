package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Class-table inheritance base for AlbumEditorial/TrackEditorial (and later
 * ArtistEditorial) — shared columns live here in `editorials`, each subtype's
 * own table just adds its FK to the owning entity (album_id, track_id, ...).
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Table(name = "editorials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class Editorial {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String dek;

    private String byline;

    private Integer readMinutes;

    @OneToMany(mappedBy = "editorial", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<EditorialBlock> blocks = new ArrayList<>();

    // Denormalized on purpose — mutated only via EditorialRepository's atomic
    // increment/decrement UPDATE queries (see LikeService), never read-modify-saved
    // in Java, to avoid lost updates under concurrent likes.
    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public void update(String title, String dek, String byline, Integer readMinutes) {
        this.title = title;
        this.dek = dek;
        this.byline = byline;
        this.readMinutes = readMinutes;
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

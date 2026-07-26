package com.jazzlogs.backend.artist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.editorial.ArtistEditorial;

@Entity
@Table(name = "artists")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Artist {

    @Id
    @GeneratedValue
    private UUID id;

    @Setter
    @Column(nullable = false)
    private String name;

    // Derived from name — kept in sync by onCreate/onUpdate, not settable directly.
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Setter
    private String spotifyArtistId;

    @Setter
    private String spotifyUrl;

    @Setter
    private String imageUrl;

    @OneToOne(mappedBy = "artist", fetch = FetchType.LAZY)
    private ArtistEditorial editorial;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Artist(String name, String spotifyArtistId, String spotifyUrl, String imageUrl) {
        this.name = name;
        this.normalizedName = Album.normalize(name);
        this.spotifyArtistId = spotifyArtistId;
        this.spotifyUrl = spotifyUrl;
        this.imageUrl = imageUrl;
    }

    // Setters above are Lombok-generated (public, required for MapStruct's
    // accessor-naming strategy). normalizedName has no setter of its own — it's
    // derived from name and resynced here on every persist/update instead.
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        normalizedName = Album.normalize(name);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        normalizedName = Album.normalize(name);
    }
}

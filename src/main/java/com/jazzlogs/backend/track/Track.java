package com.jazzlogs.backend.track;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.TrackEditorial;

/**
 * Track placement within an album (trackNumber) is NOT stored here — it
 * lives only on the CONTAINS relationship in Neo4j (see GraphService).
 */
@Entity
@Table(name = "tracks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Track {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @Setter
    private String spotifyTrackId;

    @Setter
    @Column(nullable = false)
    private String name;

    // Derived from name — kept in sync by onCreate/onUpdate, not settable directly.
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Setter
    private Integer durationMs;

    @Setter
    private String spotifyUrl;

    // Borrowed from the parent album's cover art (a track has no artwork of its
    // own on Spotify) — see SpotifyCatalogService.fetchTrack.
    @Setter
    private String imageUrl;

    @Setter
    @Column(name = "is_standout", nullable = false)
    private boolean standout;


    @Setter
    @Enumerated(EnumType.STRING)
    private VocalProfile vocalProfile;

    @Setter
    @Enumerated(EnumType.STRING)
    private Level energy;

    @Setter
    @Enumerated(EnumType.STRING)
    private Level accessibility;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "mood_intensity")
    private Level moodIntensity;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "tempo_feel")
    private TempoFeel tempoFeel;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "composition_type")
    private CompositionType compositionType;

    @OneToOne(mappedBy = "track", fetch = FetchType.LAZY)
    private TrackEditorial editorial;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Track(
        Album album,
        String spotifyTrackId,
        String name,
        Integer durationMs,
        String spotifyUrl,
        String imageUrl,
        boolean standout,
        VocalProfile vocalProfile,
        Level energy,
        Level accessibility,
        Level moodIntensity,
        TempoFeel tempoFeel,
        CompositionType compositionType
    ) {
        this.album = album;
        this.spotifyTrackId = spotifyTrackId;
        this.name = name;
        this.normalizedName = Album.normalize(name);
        this.durationMs = durationMs;
        this.spotifyUrl = spotifyUrl;
        this.imageUrl = imageUrl;
        this.standout = standout;
        this.vocalProfile = vocalProfile;
        this.energy = energy;
        this.accessibility = accessibility;
        this.moodIntensity = moodIntensity;
        this.tempoFeel = tempoFeel;
        this.compositionType = compositionType;
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

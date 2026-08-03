package com.jazzlogs.backend.album;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.editorial.AlbumEditorial;
import com.jazzlogs.backend.track.Track;

@Entity
@Table(name = "albums")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Album {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id", nullable = false)
    private Artist artist;

    @Setter
    @Column(nullable = false)
    private String name;

    // Derived from name — kept in sync by onCreate/onUpdate, not settable directly.
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Setter
    private String spotifyAlbumId;

    @Setter
    private String spotifyUrl;

    @Setter
    private String imageUrl;

    @Setter
    private Integer releaseYear;

    @Setter
    private Integer totalTracks;

    @Setter
    @Column(nullable = false)
    private String logNumber;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VocalProfile vocalProfile;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level energy;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "mood_intensity", nullable = false)
    private Level moodIntensity;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level accessibility;

    @Setter
    private Instant postedAt;

    @Setter
    private String instagramPermalink;

    // Ordered by createdAt only as a stable fallback — the real, editorial track
    // order (trackNumber) lives on the CONTAINS relationship in Neo4j, not here.
    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<Track> tracks = new ArrayList<>();

    @OneToOne(mappedBy = "album", fetch = FetchType.LAZY)
    private AlbumEditorial editorial;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Album(
        Artist artist,
        String name,
        String spotifyAlbumId,
        String spotifyUrl,
        String imageUrl,
        Integer releaseYear,
        Integer totalTracks,
        String logNumber,
        VocalProfile vocalProfile,
        Level energy,
        Level moodIntensity,
        Level accessibility,
        Instant postedAt,
        String instagramPermalink
    ) {
        this.artist = artist;
        this.name = name;
        this.normalizedName = normalize(name);
        this.spotifyAlbumId = spotifyAlbumId;
        this.spotifyUrl = spotifyUrl;
        this.imageUrl = imageUrl;
        this.releaseYear = releaseYear;
        this.totalTracks = totalTracks;
        this.logNumber = logNumber;
        this.vocalProfile = vocalProfile;
        this.energy = energy;
        this.moodIntensity = moodIntensity;
        this.accessibility = accessibility;
        this.postedAt = postedAt;
        this.instagramPermalink = instagramPermalink;
    }

    public static String normalize(String name) {
        return name.trim().toLowerCase().replaceAll("\\s+", " ");
    }


    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        normalizedName = normalize(name);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        normalizedName = normalize(name);
    }
}

package com.jazzlogs.backend.album;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.jazzlogs.backend.artist.Artist;

// Minimal support metadata only — no editorial, no own page, no admin
// curation. Resolved/created automatically from a track's own Spotify data
// (see TrackService.resolveOrCreateAlbum), never uploaded by hand.
@Entity
@Table(name = "albums")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Album {

    @Id
    @GeneratedValue
    private UUID id;

    // An album can be credited to more than one leading artist (e.g. "Know
    // What I Mean?" — Cannonball Adderley & Bill Evans) — ordered by
    // @OrderColumn, not alphabetically, so display order matches how the
    // album is actually credited.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "album_artists",
        joinColumns = @JoinColumn(name = "album_id"),
        inverseJoinColumns = @JoinColumn(name = "artist_id")
    )
    @OrderColumn(name = "position")
    private List<Artist> artists = new ArrayList<>();

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

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Album(
        List<Artist> artists,
        String name,
        String spotifyAlbumId,
        String spotifyUrl,
        String imageUrl,
        Integer releaseYear,
        Integer totalTracks
    ) {
        this.artists = new ArrayList<>(artists);
        this.name = name;
        this.normalizedName = normalize(name);
        this.spotifyAlbumId = spotifyAlbumId;
        this.spotifyUrl = spotifyUrl;
        this.imageUrl = imageUrl;
        this.releaseYear = releaseYear;
        this.totalTracks = totalTracks;
    }

    /** The album's first-credited artist — for the many call sites that only ever show one (e.g. a chat recommendation's display name). */
    public Artist getPrimaryArtist() {
        return artists.get(0);
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

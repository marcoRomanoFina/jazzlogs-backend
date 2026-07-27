package com.jazzlogs.backend.playlist;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.jazzlogs.backend.track.Track;

// One row per (playlist, track) — DB-enforced via uq_playlist_tracks_playlist_track.
// Written via PlaylistService's granular operations (addTrack/removeTrack/
// updateTrackNote/reorderTracks), each scoped to a single row or a position-only
// batch update — no clear+recreate of the whole tracklist.
@Entity
@Table(name = "playlist_tracks", uniqueConstraints = @UniqueConstraint(name = "uq_playlist_tracks_playlist_track", columnNames = {"playlist_id", "track_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaylistTrack {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false)
    private Track track;

    @Column(nullable = false)
    private int position;

    // Editorial title for this entry within the playlist — distinct from the
    // track's own name (e.g. a narrative/curated re-framing of the track).
    private String title;

    @Column(name = "curator_note", columnDefinition = "TEXT")
    private String curatorNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PlaylistTrack(Playlist playlist, Track track, int position, String title, String curatorNote) {
        this.playlist = playlist;
        this.track = track;
        this.position = position;
        this.title = title;
        this.curatorNote = curatorNote;
    }

    public void updatePosition(int position) {
        this.position = position;
    }

    public void updateDetails(String title, String curatorNote) {
        this.title = title;
        this.curatorNote = curatorNote;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

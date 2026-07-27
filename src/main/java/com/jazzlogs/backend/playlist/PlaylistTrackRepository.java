package com.jazzlogs.backend.playlist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaylistTrackRepository extends JpaRepository<PlaylistTrack, UUID> {

    // JOIN FETCH track/album/artist — PlaylistService.getPlaylistDetail needs
    // all three per row (denormalized for the frontend), so this avoids N+1
    // lazy loads instead of just findByPlaylistIdOrderByPosition.
    @Query("""
        SELECT pt FROM PlaylistTrack pt
        JOIN FETCH pt.track t
        JOIN FETCH t.album a
        JOIN FETCH a.artist
        WHERE pt.playlist.id = :playlistId
        ORDER BY pt.position
        """)
    List<PlaylistTrack> findByPlaylistIdWithTrackDetails(@Param("playlistId") UUID playlistId);

    // Single-row lookup for addTrack (existence check)/removeTrack/updateTrackNote —
    // all scoped to one (playlist, track) pair, not the whole tracklist.
    Optional<PlaylistTrack> findByPlaylistIdAndTrackId(UUID playlistId, UUID trackId);
}

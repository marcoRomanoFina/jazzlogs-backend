package com.jazzlogs.backend.playlist.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.note.dto.NoteDto;

// avgRating is null / ratingCount is 0 when no one has rated this track yet.
// myNotes is ONLY the viewer's own notes on this track (empty if none, or if
// there's no logged-in viewer) — never other users' notes; those live in
// TrackController's own paginated /tracks/{id}/notes feed.
public record PlaylistTrackDetailDto(
    UUID trackId,
    String trackName,
    Integer durationMs,
    String spotifyUrl,
    UUID albumId,
    String albumName,
    String albumImageUrl,
    UUID artistId,
    String artistName,
    int position,
    String title,
    String curatorNote,
    BigDecimal avgRating,
    long ratingCount,
    BigDecimal myRating,
    boolean listenedByCurrentUser,
    List<NoteDto> myNotes
) {
}

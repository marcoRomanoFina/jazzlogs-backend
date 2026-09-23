package com.jazzlogs.backend.playlist.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.note.dto.NoteDto;

// Same as PlaylistTrackDetailDto, minus albumImageUrl — the featured
// playlist's track list doesn't need per-track artwork.
public record FeaturedPlaylistTrackDto(
    UUID trackId,
    String trackName,
    Integer durationMs,
    String spotifyUrl,
    UUID albumId,
    String albumName,
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

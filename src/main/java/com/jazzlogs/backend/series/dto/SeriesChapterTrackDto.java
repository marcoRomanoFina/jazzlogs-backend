package com.jazzlogs.backend.series.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.note.dto.NoteDto;

// No vocabulary tags here on purpose (moods/contexts/rhythms/featuredInstruments,
// performers, editorial) — same trim as PlaylistTrackDetailDto, not the full
// TrackDto. avgRating/ratingCount are null/0 when no one has rated it yet.
// myNotes is ONLY the current user's own notes on this track (empty if none,
// or if there's no logged-in viewer).
public record SeriesChapterTrackDto(
    UUID id,
    String name,
    Integer durationMs,
    String spotifyUrl,
    String imageUrl,
    UUID albumId,
    String albumName,
    UUID artistId,
    String artistName,
    BigDecimal avgRating,
    long ratingCount,
    BigDecimal myRating,
    boolean hasListened,
    List<NoteDto> myNotes
) {
}

package com.jazzlogs.backend.track.dto;

import jakarta.validation.constraints.NotBlank;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.track.CompositionType;
import com.jazzlogs.backend.track.TempoFeel;

/**
 * spotifyTrackId is required — name/durationMs/spotifyUrl/trackNumber all come
 * from Spotify (see TrackService.addTrack), not from this request. trackNumber
 * isn't a column on tracks either way — it only feeds the CONTAINS relationship
 * property in Neo4j. trackRole is optional; defaults to "MAIN". The 6 editorial
 * fields stay optional — they're nullable columns, filled in later via PATCH.
 */
public record CreateTrackRequest(
    String trackRole,
    @NotBlank String spotifyTrackId,
    boolean standout,
    VocalProfile vocalProfile,
    Level energy,
    Level accessibility,
    Level moodIntensity,
    TempoFeel tempoFeel,
    CompositionType compositionType
) {
}

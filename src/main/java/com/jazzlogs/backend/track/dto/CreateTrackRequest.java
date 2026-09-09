package com.jazzlogs.backend.track.dto;

import jakarta.validation.constraints.NotBlank;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.track.CompositionType;
import com.jazzlogs.backend.track.TempoFeel;

/**
 * spotifyTrackId is required — name/durationMs/spotifyUrl come from Spotify
 * (see TrackService.createOrUpdateTrack), not from this request. trackNumber
 * isn't here at all: it's assigned by upload order, not by Spotify's own
 * numbering (which counts bonus/alternate takes we often skip), and only
 * feeds the CONTAINS relationship property in Neo4j — it's not a column on
 * tracks. The 6 editorial fields stay optional — they're nullable columns,
 * re-postable any time via the same upsert.
 */
public record CreateTrackRequest(
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

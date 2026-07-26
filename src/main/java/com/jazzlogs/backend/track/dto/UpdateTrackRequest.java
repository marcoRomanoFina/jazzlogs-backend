package com.jazzlogs.backend.track.dto;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.track.CompositionType;
import com.jazzlogs.backend.track.TempoFeel;

/**
 * Partial update — every field is optional; only non-null fields are applied.
 * Placement within the album (trackNumber/trackRole) isn't editable here.
 */
public record UpdateTrackRequest(
    String spotifyTrackId,
    String logNumber,
    String name,
    Integer durationMs,
    String spotifyUrl,
    String imageUrl,
    Boolean standout,
    VocalProfile vocalProfile,
    Level energy,
    Level accessibility,
    Level moodIntensity,
    TempoFeel tempoFeel,
    CompositionType compositionType
) {
}

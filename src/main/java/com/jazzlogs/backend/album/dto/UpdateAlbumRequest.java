package com.jazzlogs.backend.album.dto;

import java.time.Instant;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;

/**
 * Partial update — every field is optional; only non-null fields are applied.
 */
public record UpdateAlbumRequest(
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
}

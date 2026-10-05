package com.jazzlogs.backend.artist.dto;

import java.util.UUID;

/**
 * One artist credited on an album/track, flattened enough to display and
 * link to without a separate lookup — an album can be credited to more than
 * one of these (e.g. "Know What I Mean?" — Cannonball Adderley & Bill Evans),
 * ordered the way the album is actually credited.
 *
 * @param id        the artist's own id
 * @param name      the artist's name
 * @param imageUrl  the artist's photo
 * @param spotifyUrl link to the artist's Spotify page
 */
public record ArtistSummaryDto(UUID id, String name, String imageUrl, String spotifyUrl) {
}

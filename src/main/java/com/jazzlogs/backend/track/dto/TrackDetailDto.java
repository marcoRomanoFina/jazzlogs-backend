package com.jazzlogs.backend.track.dto;

import java.util.UUID;

/**
 * The track detail page's full payload — the track's own everything (see
 * {@link TrackDto}) plus enough about its artist/album to render without a
 * separate lookup, same flattening {@code AlbumSummaryDto} uses for its own
 * parent context.
 *
 * @param artistId         the track's artist (via its album)
 * @param artistName       the artist's name
 * @param artistImageUrl   the artist's photo
 * @param artistSpotifyUrl link to the artist's Spotify page
 * @param albumId          the track's album
 * @param albumName        the album's name
 * @param albumImageUrl    the album's cover art
 * @param albumSpotifyUrl  link to the album's Spotify page
 * @param albumReleaseYear year of the album's original release
 * @param track            the track's own full data
 */
public record TrackDetailDto(
    UUID artistId,
    String artistName,
    String artistImageUrl,
    String artistSpotifyUrl,
    UUID albumId,
    String albumName,
    String albumImageUrl,
    String albumSpotifyUrl,
    Integer albumReleaseYear,
    TrackDto track
) {
}

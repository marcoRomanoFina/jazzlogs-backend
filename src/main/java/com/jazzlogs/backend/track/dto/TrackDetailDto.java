package com.jazzlogs.backend.track.dto;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.artist.dto.ArtistSummaryDto;

/**
 * The track detail page's full payload — the track's own everything (see
 * {@link TrackDto}) plus enough about its artists/album to render without a
 * separate lookup, same flattening {@code AlbumSummaryDto} uses for its own
 * parent context.
 *
 * @param artists          the album's credited artist(s), in credited order
 * @param albumId          the track's album
 * @param albumName        the album's name
 * @param albumImageUrl    the album's cover art
 * @param albumSpotifyUrl  link to the album's Spotify page
 * @param albumReleaseYear year of the album's original release
 * @param track            the track's own full data
 */
public record TrackDetailDto(
    List<ArtistSummaryDto> artists,
    UUID albumId,
    String albumName,
    String albumImageUrl,
    String albumSpotifyUrl,
    Integer albumReleaseYear,
    TrackDto track
) {
}

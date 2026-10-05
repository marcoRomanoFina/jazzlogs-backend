package com.jazzlogs.backend.artist.dto;

import java.util.List;
import java.util.UUID;

/**
 * One album row on an artist page's sideman albums list ({@code
 * ArtistService#getSidemanAlbums}). {@code artists} are the album's own
 * leading artist(s), not the page's own artist — carried here anyway so the
 * frontend doesn't need a separate lookup to render it.
 *
 * @param id          the album's own id
 * @param name        the album's name
 * @param imageUrl    cover art
 * @param releaseYear year of original release
 * @param totalTracks how many tracks are catalogued so far, by upload order — not Spotify's own count (see {@code TrackService#createOrUpdateTrack})
 * @param artists     the album's own leading artist(s), in credited order
 */
public record AlbumSummaryDto(
    UUID id,
    String name,
    String imageUrl,
    Integer releaseYear,
    Integer totalTracks,
    List<ArtistSummaryDto> artists
) {
}

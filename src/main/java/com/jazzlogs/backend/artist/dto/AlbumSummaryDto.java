package com.jazzlogs.backend.artist.dto;

import java.util.UUID;

/**
 * One album row on an artist page's album lists — "Essential Listening"
 * ({@code ArtistService#getEssentialListening}) and sideman albums ({@code
 * ArtistService#getSidemanAlbums}) share this exact shape. {@code
 * artistId}/{@code artistName} are the album's own leading artist — for
 * essential listening that's always the page's own artist ({@code
 * AlbumService#markEntryPoint} enforces that at write time), but for sideman
 * albums it's someone else, carried here anyway so the frontend doesn't need
 * a separate lookup to render it.
 *
 * @param id          the album's own id
 * @param name        the album's name
 * @param imageUrl    cover art
 * @param releaseYear year of original release
 * @param totalTracks how many tracks are catalogued so far, by upload order — not Spotify's own count (see {@code TrackService#createOrUpdateTrack})
 * @param artistId    the album's own leading artist
 * @param artistName  the album's own leading artist's name
 */
public record AlbumSummaryDto(
    UUID id,
    String name,
    String imageUrl,
    Integer releaseYear,
    Integer totalTracks,
    UUID artistId,
    String artistName
) {
}

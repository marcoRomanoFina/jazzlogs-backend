package com.jazzlogs.backend.album.dto;

import java.util.UUID;

/**
 * An album's minimal support metadata — no editorial, no own page, no
 * admin-curated fields. Resolved/created automatically from a track's own
 * Spotify data (see {@code TrackService#resolveOrCreateAlbum}). {@code
 * totalTracks} is the one field not sourced from Spotify — it's how many
 * tracks JazzLogs has actually catalogued under this album (see {@code
 * AlbumService#getAlbumHeader}).
 *
 * @param id             the album's own id
 * @param artistId       the album's artist
 * @param artistName     the artist's name
 * @param name           the album's name
 * @param spotifyAlbumId Spotify's own id for this album
 * @param spotifyUrl     link to play it on Spotify
 * @param imageUrl       cover art
 * @param releaseYear    year of original release
 * @param totalTracks    how many tracks are catalogued so far, by upload
 *                       order — not Spotify's own track count, which can
 *                       include bonus/alternate takes we skip
 */
public record AlbumHeaderDto(
    UUID id,
    UUID artistId,
    String artistName,
    String name,
    String spotifyAlbumId,
    String spotifyUrl,
    String imageUrl,
    Integer releaseYear,
    Integer totalTracks
) {
}

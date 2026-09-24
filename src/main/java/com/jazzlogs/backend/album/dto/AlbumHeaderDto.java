package com.jazzlogs.backend.album.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.graph.AlbumPersonnelEntry;

/**
 * The album page's fast, above-the-fold load — everything except the track
 * list, which the page fetches separately (see {@code
 * AlbumService#getAlbumTracks}) since it's the expensive part (5 Neo4j
 * calls per track, notes, ratings, ...). See {@code
 * AlbumService#getAlbumHeader}.
 *
 * @param id                 the album's own id
 * @param artistId           the album's artist
 * @param artistName         the artist's name
 * @param name               the album's name
 * @param spotifyAlbumId     Spotify's own id for this album
 * @param spotifyUrl         link to play it on Spotify
 * @param imageUrl           cover art
 * @param releaseYear        year of original release
 * @param totalTracks        how many tracks are catalogued so far, by upload
 *                           order — not Spotify's own track count, which
 *                           can include bonus/alternate takes we skip
 * @param postedAt           when this album was first added to JazzLogs
 * @param instagramPermalink optional link to an Instagram post about this album
 * @param coverColor         admin-curated accent color, hex (e.g. {@code #a86b32}),
 *                           {@code null} until explicitly set — the frontend falls
 *                           back to its own automatic sampling in that case
 * @param letterColor        admin-curated text color, hex, {@code null} until explicitly
 *                           set — the frontend falls back to its own default in that case
 * @param styles             style tag labels, from Neo4j
 * @param moods              mood tag labels, from Neo4j
 * @param contexts           context tag labels, from Neo4j
 * @param personnel          sidemen/personnel credited on the album, from Neo4j
 * @param hasListened        derived, live, from every track being listened —
 *                           not a flag the user sets directly (see {@code AlbumService#getAlbumHeader})
 * @param listenedTrackCount how many of the album's tracks the current user has listened to
 * @param listenCount        total plays across every user
 * @param isSaved            whether the current user has saved this album
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
    Integer totalTracks,
    Instant postedAt,
    String instagramPermalink,
    String coverColor,
    String letterColor,
    List<String> styles,
    List<String> moods,
    List<String> contexts,
    List<AlbumPersonnelEntry> personnel,
    boolean hasListened,
    int listenedTrackCount,
    long listenCount,
    boolean isSaved
) {
}

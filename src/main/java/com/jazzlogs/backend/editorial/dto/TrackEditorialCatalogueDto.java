package com.jazzlogs.backend.editorial.dto;

import java.time.Instant;
import java.util.UUID;

import com.jazzlogs.backend.character.JazzlogsCharacter;

/**
 * A log's card: one row in the archive's search/browse listing, and what a
 * chat shows for each track it recommended. Always a track editorial (the
 * only kind left), so unlike the old multi-owner-type catalogue this carries
 * no {@code type} field.
 *
 * @param id                 the track editorial's own id
 * @param trackId            the track this editorial belongs to
 * @param trackName          the track's own name
 * @param durationMs         the track's length in milliseconds
 * @param spotifyUrl         link to play the track on Spotify
 * @param editorialCoverUrl  the editorial's cover image
 * @param albumName          the track's album
 * @param albumId            the track's album id
 * @param artistName         the track's artist
 * @param title              the editorial's headline
 * @param logNumber          the JazzLogs log number
 * @param dek                short standfirst text
 * @param byline             who wrote it
 * @param createdAt          when this editorial was created
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService}
 */
public record TrackEditorialCatalogueDto(
    UUID id,
    UUID trackId,
    String trackName,
    Integer durationMs,
    String spotifyUrl,
    String editorialCoverUrl,
    String albumName,
    UUID albumId,
    String artistName,
    String title,
    String logNumber,
    String dek,
    JazzlogsCharacter byline,
    Instant createdAt,
    int likeCount,
    boolean likedByCurrentUser
) {
}

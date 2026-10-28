package com.jazzlogs.backend.note.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's note on one track.
 *
 * @param id                 the note's own id
 * @param trackId            the track this note is about
 * @param userId             the author
 * @param userName           the author's display name
 * @param title              required
 * @param text               required
 * @param timestampSeconds   optional — a specific moment in the track this note is about
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService} — against the viewer, not the author
 * @param createdAt          when this note was posted
 */
public record NoteDto(
    UUID id,
    UUID trackId,
    UUID userId,
    String userName,
    String title,
    String text,
    Integer timestampSeconds,
    int likeCount,
    boolean likedByCurrentUser,
    Instant createdAt
) {
}

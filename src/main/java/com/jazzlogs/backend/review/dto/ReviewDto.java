package com.jazzlogs.backend.review.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.note.dto.NoteDto;

/**
 * One user's review of one album.
 *
 * @param id                 the review's own id
 * @param albumId            the reviewed album
 * @param userId             the reviewer
 * @param userName           the reviewer's display name
 * @param rating             1 to 5, in 0.5 steps
 * @param text               optional — a rating alone is a valid review
 * @param likeCount          denormalized total, kept in sync via atomic increment/decrement
 * @param likedByCurrentUser computed separately via {@code LikeService} — against the viewer, not the reviewer
 * @param standoutTracks     tracks the reviewer called out, always belonging to {@code albumId}
 * @param notes              full {@code NoteDto}s (not a lean summary) — the frontend renders/opens
 *                           these exactly like the per-track note feed, so they need the same shape
 * @param createdAt          when this review was first posted
 * @param updatedAt          when it was last edited
 */
public record ReviewDto(
    UUID id,
    UUID albumId,
    UUID userId,
    String userName,
    BigDecimal rating,
    String text,
    int likeCount,
    boolean likedByCurrentUser,
    List<StandoutTrackDto> standoutTracks,
    List<NoteDto> notes,
    Instant createdAt,
    Instant updatedAt
) {
}

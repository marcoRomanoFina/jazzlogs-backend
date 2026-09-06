package com.jazzlogs.backend.editorial.dto;

import java.util.List;

/**
 * A track's own editorial, as nested in {@code TrackDto}. No {@code id}/
 * {@code likeCount}/{@code likedByCurrentUser} here, unlike {@code
 * AlbumEditorialDto} — {@code TrackController.upsertEditorial} exposes
 * those separately when editing, and the album detail page doesn't
 * currently surface a per-track-editorial like action.
 *
 * @param title  the editorial's headline
 * @param dek    short standfirst text
 * @param byline who wrote it
 * @param blocks the editorial's body, in order
 */
public record TrackEditorialDto(String title, String dek, String byline, List<EditorialBlockDto> blocks) {
}

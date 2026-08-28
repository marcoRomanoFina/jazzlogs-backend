package com.jazzlogs.backend.editorial.dto;

/** Same shape as {@code LikeCountResponse} — a bare count, wrapped so the response is a JSON object, not a raw number. */
public record EditorialCountResponse(long count) {
}

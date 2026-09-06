package com.jazzlogs.backend.review.dto;

import java.util.UUID;

/**
 * @param id   the track's own id
 * @param name the track's name
 */
public record StandoutTrackDto(UUID id, String name) {
}

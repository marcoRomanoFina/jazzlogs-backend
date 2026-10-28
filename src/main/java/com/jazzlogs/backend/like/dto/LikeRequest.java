package com.jazzlogs.backend.like.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.like.LikeableEntityType;

/**
 * Request body identifying one likeable entity.
 *
 * @param entityType which kind of entity — see {@link LikeableEntityType}
 * @param entityId   that entity's own id
 */
public record LikeRequest(@NotNull LikeableEntityType entityType, @NotNull UUID entityId) {
}

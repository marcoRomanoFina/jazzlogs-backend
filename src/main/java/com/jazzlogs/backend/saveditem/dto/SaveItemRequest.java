package com.jazzlogs.backend.saveditem.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.saveditem.SaveableEntityType;

/**
 * Request body identifying one saveable entity.
 *
 * @param entityType which kind of entity — see {@link SaveableEntityType}
 * @param entityId   that entity's own id
 */
public record SaveItemRequest(@NotNull SaveableEntityType entityType, @NotNull UUID entityId) {
}

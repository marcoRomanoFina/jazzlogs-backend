package com.jazzlogs.backend.saveditem.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.saveditem.SaveableEntityType;

public record SaveItemRequest(@NotNull SaveableEntityType entityType, @NotNull UUID entityId) {
}

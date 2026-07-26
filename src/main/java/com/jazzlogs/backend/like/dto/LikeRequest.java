package com.jazzlogs.backend.like.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.jazzlogs.backend.like.LikeableEntityType;

public record LikeRequest(@NotNull LikeableEntityType entityType, @NotNull UUID entityId) {
}

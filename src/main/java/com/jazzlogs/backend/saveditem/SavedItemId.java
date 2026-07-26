package com.jazzlogs.backend.saveditem;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public record SavedItemId(
    @Column(name = "user_id") UUID userId,
    @Enumerated(EnumType.STRING) @Column(name = "entity_type") SaveableEntityType entityType,
    @Column(name = "entity_id") UUID entityId
) {
}

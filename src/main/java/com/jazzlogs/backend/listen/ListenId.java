package com.jazzlogs.backend.listen;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public record ListenId(
    @Column(name = "user_id") UUID userId,
    @Enumerated(EnumType.STRING) @Column(name = "entity_type") ListenableEntityType entityType,
    @Column(name = "entity_id") UUID entityId
) {
}

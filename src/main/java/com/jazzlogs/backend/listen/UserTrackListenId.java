package com.jazzlogs.backend.listen;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record UserTrackListenId(
    @Column(name = "user_id") UUID userId,
    @Column(name = "track_id") UUID trackId
) {
}

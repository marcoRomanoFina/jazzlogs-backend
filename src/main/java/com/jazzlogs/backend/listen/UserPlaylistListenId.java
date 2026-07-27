package com.jazzlogs.backend.listen;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record UserPlaylistListenId(
    @Column(name = "user_id") UUID userId,
    @Column(name = "playlist_id") UUID playlistId
) {
}

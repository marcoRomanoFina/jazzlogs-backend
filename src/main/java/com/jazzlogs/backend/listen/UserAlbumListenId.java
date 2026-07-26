package com.jazzlogs.backend.listen;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record UserAlbumListenId(
    @Column(name = "user_id") UUID userId,
    @Column(name = "album_id") UUID albumId
) {
}

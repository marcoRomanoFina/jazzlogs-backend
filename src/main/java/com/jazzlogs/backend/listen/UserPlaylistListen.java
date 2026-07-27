package com.jazzlogs.backend.listen;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// user_id/playlist_id are real foreign keys, same reasoning as UserAlbumListen —
// Playlist is a concrete, non-polymorphic target. Postgres-only: playlist
// listens have no Neo4j mirror (see ListenService.markPlaylistListened).
@Entity
@Table(name = "user_playlist_listens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPlaylistListen {

    @EmbeddedId
    private UserPlaylistListenId id;

    @Column(name = "listened_at", nullable = false)
    private Instant listenedAt;

    public UserPlaylistListen(UserPlaylistListenId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        listenedAt = Instant.now();
    }
}

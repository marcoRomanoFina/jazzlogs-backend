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

// user_id/album_id are real foreign keys (see migration SQL) — unlike likes,
// Album is a concrete, non-polymorphic target, so there's no reason to give
// up referential integrity here.
@Entity
@Table(name = "user_album_listens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAlbumListen {

    @EmbeddedId
    private UserAlbumListenId id;

    @Column(name = "listened_at", nullable = false)
    private Instant listenedAt;

    public UserAlbumListen(UserAlbumListenId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        listenedAt = Instant.now();
    }
}

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

@Entity
@Table(name = "user_track_listens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTrackListen {

    @EmbeddedId
    private UserTrackListenId id;

    @Column(name = "listened_at", nullable = false)
    private Instant listenedAt;

    public UserTrackListen(UserTrackListenId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        listenedAt = Instant.now();
    }
}

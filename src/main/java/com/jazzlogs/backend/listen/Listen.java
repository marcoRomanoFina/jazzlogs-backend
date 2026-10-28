package com.jazzlogs.backend.listen;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// entity_id is deliberately not a foreign key — Album, Track, Playlist and
// SeriesChapter are unrelated domain entities with no common superclass, same
// reasoning as Like/SavedItem's polymorphic entity_id. Existence of entityId is
// validated in ListenService before insert (getAlbumOrThrow, etc.), not by the
// database.
@Entity
@Table(name = "listens", indexes = @Index(name = "idx_listens_entity", columnList = "entity_type, entity_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Listen {

    @EmbeddedId
    private ListenId id;

    @Column(name = "listened_at", nullable = false)
    private Instant listenedAt;

    public Listen(ListenId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        listenedAt = Instant.now();
    }
}

package com.jazzlogs.backend.like;

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

// entity_id is deliberately not a foreign key — Editorial, Review, Playlist, Note
// and Series are unrelated domain entities with no common superclass. Existence
// of entityId is validated in LikeService before insert, not by the database.
@Entity
@Table(name = "likes", indexes = @Index(name = "idx_likes_entity", columnList = "entity_type, entity_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Like {

    @EmbeddedId
    private LikeId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Like(LikeId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

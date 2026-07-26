package com.jazzlogs.backend.saveditem;

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

// entity_id is deliberately not a foreign key — Album, Track and (eventually)
// Playlist are unrelated domain entities with no common superclass. Existence
// of entityId is validated in SavedItemService before insert, not by the
// database. No updated_at — a save is binary (exists or doesn't), never edited.
@Entity
@Table(name = "saved_items", indexes = @Index(name = "idx_saved_items_entity", columnList = "entity_type, entity_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedItem {

    @EmbeddedId
    private SavedItemId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SavedItem(SavedItemId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}

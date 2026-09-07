package com.jazzlogs.backend.saveditem;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;

// entity_id is deliberately not a foreign key — Album, Track and (eventually)
// Playlist are unrelated domain entities with no common superclass. Existence
// of entityId is validated in SavedItemService before insert, not by the
// database. No updated_at — a save is binary (exists or doesn't), never edited.
//
// Persistable<SavedItemId>: id is a manually assigned @EmbeddedId, always
// non-null once constructed — without this, Spring Data's default "is it
// new?" check (id == null) always says "no", so save() would call
// entityManager.merge() (an extra SELECT, then an INSERT deferred to flush)
// instead of persist() for every brand-new SavedItem. Same fix as Like — see
// its comment for the full story. isNew defaults true and flips false the
// moment this instance is either actually persisted or loaded back from the DB.
@Entity
@Table(name = "saved_items", indexes = @Index(name = "idx_saved_items_entity", columnList = "entity_type, entity_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedItem implements Persistable<SavedItemId> {

    @EmbeddedId
    private SavedItemId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    public SavedItem(SavedItemId id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        isNew = false;
    }

    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}

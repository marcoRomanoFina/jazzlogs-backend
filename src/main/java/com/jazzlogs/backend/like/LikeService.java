package com.jazzlogs.backend.like;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.editorial.EditorialRepository;
import com.jazzlogs.backend.note.NoteRepository;
import com.jazzlogs.backend.playlist.PlaylistRepository;
import com.jazzlogs.backend.review.ReviewRepository;
import com.jazzlogs.backend.series.SeriesRepository;

@Service
public class LikeService {

    private final LikeRepository likeRepository;
    private final Map<LikeableEntityType, LikeableRepository<?>> repositories;
    private final EntityManager entityManager;

    /**
     * Add a repository param + a repositories entry per new likeable type as
     * each one gets built — no switch to touch, existence-checking/counting/
     * increment/decrement all dispatch off this one map.
     */
    public LikeService(
        LikeRepository likeRepository,
        EditorialRepository editorialRepository,
        NoteRepository noteRepository,
        ReviewRepository reviewRepository,
        PlaylistRepository playlistRepository,
        SeriesRepository seriesRepository,
        EntityManager entityManager
    ) {
        this.likeRepository = likeRepository;
        this.repositories = Map.of(
            LikeableEntityType.EDITORIAL, editorialRepository,
            LikeableEntityType.NOTE, noteRepository,
            LikeableEntityType.REVIEW, reviewRepository,
            LikeableEntityType.PLAYLIST, playlistRepository,
            LikeableEntityType.SERIES, seriesRepository
        );
        this.entityManager = entityManager;
    }

    /**
     * Likes an entity on the caller's behalf. Idempotent — liking something
     * already liked is a no-op, not an error.
     *
     * @param userId     who is liking it
     * @param entityType which kind of entity
     * @param entityId   that entity's own id
     * @return true if this call created the like, false if the user had already liked it
     */
    @Transactional
    public boolean addLike(UUID userId, LikeableEntityType entityType, UUID entityId) {
        assertEntityExists(entityType, entityId);

        LikeId id = new LikeId(userId, entityType, entityId);
        if (likeRepository.existsById(id)) {
            return false;
        }
        try {
            // flush() forces the INSERT to run right here instead of at
            // commit time — without it, save() alone just queues the insert,
            // and a race with another request would blow up much later (at
            // commit, well outside this try/catch) instead of being caught
            // below. Both exception types: flush() bypasses Spring's
            // repository-method AOP exception translation, so the raw
            // Hibernate ConstraintViolationException surfaces here, not
            // Spring's DataIntegrityViolationException.
            likeRepository.save(new Like(id));
            entityManager.flush();
        } catch (DataIntegrityViolationException | ConstraintViolationException concurrentLike) {
            // Another request inserted the same like between our exists() check
            // and save() — the end state is identical, so this is still success,
            // and that request already incremented the counter.
            return false;
        }
        repository(entityType).incrementLikeCount(entityId);
        return true;
    }

    /**
     * Idempotent — does nothing if the like didn't exist.
     */
    @Transactional
    public void removeLike(UUID userId, LikeableEntityType entityType, UUID entityId) {
        int deleted = likeRepository.deleteByUserIdAndEntityTypeAndEntityId(userId, entityType, entityId);
        if (deleted > 0) {
            repository(entityType).decrementLikeCount(entityId);
        }
    }

  
    @Transactional(readOnly = true)
    public long countLikes(LikeableEntityType entityType, UUID entityId) {
        LikeableRepository<?> repository = repositories.get(entityType);
        return repository == null ? 0 : repository.findLikeCount(entityId).orElse(0);
    }

    @Transactional(readOnly = true)
    public boolean hasUserLiked(UUID userId, LikeableEntityType entityType, UUID entityId) {
        return likeRepository.existsById(new LikeId(userId, entityType, entityId));
    }

    /**
     * One query for the whole list — the returned set is the subset of entityIds
     * this user liked, for marking hearts active across a feed without N calls.
     */
    @Transactional(readOnly = true)
    public Set<UUID> hasUserLikedBatch(UUID userId, LikeableEntityType entityType, List<UUID> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(likeRepository.findLikedEntityIds(userId, entityType, entityIds));
    }

    /** @throws ResponseStatusException 404 if no entity of that type/id exists */
    private void assertEntityExists(LikeableEntityType entityType, UUID entityId) {
        if (!repository(entityType).existsById(entityId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, entityType + " not found: " + entityId);
        }
    }

    /**
     * @throws ResponseStatusException 501 if this entityType has no entry in {@link #repositories} yet
     */
    private LikeableRepository<?> repository(LikeableEntityType entityType) {
        LikeableRepository<?> repository = repositories.get(entityType);
        if (repository == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, entityType + " likes aren't wired up yet");
        }
        return repository;
    }
}

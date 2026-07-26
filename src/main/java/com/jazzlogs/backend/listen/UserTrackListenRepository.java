package com.jazzlogs.backend.listen;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTrackListenRepository extends JpaRepository<UserTrackListen, UserTrackListenId> {

    /**
     * Idempotent — returns true if this call created the row, false if the user
     * had already marked this track as listened (no-op, not an error).
     */
    default boolean insertIfNotExists(UUID userId, UUID trackId) {
        UserTrackListenId id = new UserTrackListenId(userId, trackId);
        if (existsById(id)) {
            return false;
        }
        try {
            save(new UserTrackListen(id));
            return true;
        } catch (DataIntegrityViolationException concurrentListen) {
            return false;
        }
    }
}

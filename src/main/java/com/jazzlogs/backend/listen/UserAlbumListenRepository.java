package com.jazzlogs.backend.listen;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAlbumListenRepository extends JpaRepository<UserAlbumListen, UserAlbumListenId> {

    /**
     * Idempotent — returns true if this call created the row, false if the user
     * had already marked this album as listened (no-op, not an error). Same
     * exists-check-then-save-with-race-catch pattern as LikeService.addLike.
     */
    default boolean insertIfNotExists(UUID userId, UUID albumId) {
        UserAlbumListenId id = new UserAlbumListenId(userId, albumId);
        if (existsById(id)) {
            return false;
        }
        try {
            save(new UserAlbumListen(id));
            return true;
        } catch (DataIntegrityViolationException concurrentListen) {
            return false;
        }
    }
}

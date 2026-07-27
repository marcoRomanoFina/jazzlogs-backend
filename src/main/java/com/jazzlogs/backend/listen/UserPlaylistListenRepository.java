package com.jazzlogs.backend.listen;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPlaylistListenRepository extends JpaRepository<UserPlaylistListen, UserPlaylistListenId> {

    /**
     * Idempotent — returns true if this call created the row, false if the user
     * had already marked this playlist as listened (no-op, not an error). Same
     * exists-check-then-save-with-race-catch pattern as UserAlbumListenRepository.
     */
    default boolean insertIfNotExists(UUID userId, UUID playlistId) {
        UserPlaylistListenId id = new UserPlaylistListenId(userId, playlistId);
        if (existsById(id)) {
            return false;
        }
        try {
            save(new UserPlaylistListen(id));
            return true;
        } catch (DataIntegrityViolationException concurrentListen) {
            return false;
        }
    }

    // Idempotent — returns rows deleted (0 or 1), same delete-without-throwing
    // contract as SavedItemRepository.deleteByUserIdAndEntityTypeAndEntityId.
    @Modifying
    @Query("DELETE FROM UserPlaylistListen l WHERE l.id.userId = :userId AND l.id.playlistId = :playlistId")
    int deleteByUserIdAndPlaylistId(@Param("userId") UUID userId, @Param("playlistId") UUID playlistId);
}

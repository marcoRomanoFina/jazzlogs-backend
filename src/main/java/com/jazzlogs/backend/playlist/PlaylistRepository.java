package com.jazzlogs.backend.playlist;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;
import com.jazzlogs.backend.saveditem.SavedItemResolver;

// LikeableRepository<Playlist>: same atomic-UPDATE like_count pattern as
// Editorial/Note/Review. SavedItemResolver: wires PLAYLIST into
// SavedItemService's resolver map (SaveableEntityType already had PLAYLIST
// prepared, this is what fulfills it).
public interface PlaylistRepository extends LikeableRepository<Playlist>, SavedItemResolver {

    @Modifying
    @Query("UPDATE Playlist p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Playlist p SET p.likeCount = GREATEST(p.likeCount - 1, 0) WHERE p.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT p.likeCount FROM Playlist p WHERE p.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    Optional<Playlist> findBySlug(String slug);

    List<Playlist> findByPublishedTrueOrderByCreatedAtDesc();

    List<Playlist> findAllByOrderByCreatedAtDesc();

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(PlaylistRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Playlist::getId, PlaylistRepository::toResolved));
    }

    private static Resolved toResolved(Playlist playlist) {
        return new Resolved(playlist.getTitle(), playlist.getCoverImageUrl(), playlist.getSpotifyUrl());
    }
}

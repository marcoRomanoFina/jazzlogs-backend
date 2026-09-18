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

    List<Playlist> findByPublishedTrueOrderByCreatedAtDesc();

    List<Playlist> findAllByOrderByCreatedAtDesc();

    /** For {@code PlaylistService.assertTitleAvailable} — uq_playlists_title (V29) is the DB-level backstop. */
    Optional<Playlist> findByTitle(String title);

    /** For {@code PlaylistService.getFeatured} — the singleton featured playlist, if any is currently featured. */
    Optional<Playlist> findByFeaturedTrue();

    /**
     * The normal-path way to clear the previous featured row before marking
     * a new one — {@code PlaylistService.setFeatured} calls this before
     * {@link #markFeatured}, both as atomic UPDATEs rather than
     * read-modify-save. This alone doesn't guarantee at most one stays
     * featured under concurrent calls; {@code idx_playlists_only_one_featured}
     * (see V26) is what actually does — same pattern as {@code AlbumRepository}.
     */
    @Modifying
    @Query("UPDATE Playlist p SET p.featured = false WHERE p.featured = true")
    void clearFeatured();

    /** See {@link #clearFeatured} — always called right after it, never on its own. */
    @Modifying
    @Query("UPDATE Playlist p SET p.featured = true WHERE p.id = :id")
    void markFeatured(@Param("id") UUID id);

    /** A no-op if {@code id} wasn't featured to begin with. */
    @Modifying
    @Query("UPDATE Playlist p SET p.featured = false WHERE p.id = :id")
    void unmarkFeatured(@Param("id") UUID id);

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
        return new Resolved(playlist.getTitle(), playlist.getCoverImageUrl());
    }
}

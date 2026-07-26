package com.jazzlogs.backend.note;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.like.LikeableRepository;

public interface NoteRepository extends LikeableRepository<Note> {

    // Same atomic-UPDATE pattern as EditorialRepository — not read-modify-save.
    @Modifying
    @Query("UPDATE Note n SET n.likeCount = n.likeCount + 1 WHERE n.id = :id")
    void incrementLikeCount(@Param("id") UUID entityId);

    @Modifying
    @Query("UPDATE Note n SET n.likeCount = GREATEST(n.likeCount - 1, 0) WHERE n.id = :id")
    void decrementLikeCount(@Param("id") UUID entityId);

    @Query("SELECT n.likeCount FROM Note n WHERE n.id = :id")
    Optional<Integer> findLikeCount(@Param("id") UUID entityId);

    List<Note> findByTrackIdOrderByCreatedAtAsc(UUID trackId);

    List<Note> findByTrackIdAndUserIdOrderByCreatedAtAsc(UUID trackId, UUID userId);

    // For AlbumService.getAlbumDetail — one query for every note the current
    // user left on any track of this album, instead of one per track.
    @Query("SELECT n FROM Note n WHERE n.user.id = :userId AND n.track.album.id = :albumId")
    List<Note> findByUserAndAlbum(@Param("userId") UUID userId, @Param("albumId") UUID albumId);
}

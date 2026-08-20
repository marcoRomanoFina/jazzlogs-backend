package com.jazzlogs.backend.track;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.saveditem.SavedItemResolver;

public interface TrackRepository extends JpaRepository<Track, UUID>, SavedItemResolver, CatalogEntityResolver {

    // Spotify identity is what POST /albums/{id}/tracks upserts on — a track
    // with this spotifyTrackId already existing means "update it", not
    // "create a duplicate".
    Optional<Track> findBySpotifyTrackId(String spotifyTrackId);

    // For ListenService.syncAlbumCompletionState — needs every track id on
    // the album to check whether the user has now listened to all of them.
    @Query("SELECT t.id FROM Track t WHERE t.album.id = :albumId")
    List<UUID> findIdsByAlbumId(@Param("albumId") UUID albumId);

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(TrackRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Track::getId, TrackRepository::toResolved));
    }

    private static Resolved toResolved(Track track) {
        return new Resolved(track.getName(), track.getImageUrl(), track.getSpotifyUrl());
    }

    // JOIN FETCH, not the default findAllById — ChatExchangeService.resolveWinners
    // needs each track's album AND that album's artist (track.album and
    // album.artist are both FetchType.LAZY); without this, resolving N
    // recommended tracks means up to 2N extra per-row SELECTs (one for the
    // album, one for the artist) instead of one batched query.
    @Query("SELECT t FROM Track t JOIN FETCH t.album al JOIN FETCH al.artist WHERE t.id IN :ids")
    List<Track> findAllByIdWithAlbumAndArtist(@Param("ids") List<UUID> ids);

    // Same matchType/ordering shape as ArtistRepository.search — see its
    // comment. Two-hop join (track -> album -> artist): a track's artist is
    // its album's direct artist_id, no sideman/graph resolution here.
    @Override
    @Query(value = """
        SELECT
            t.id AS id,
            t.name AS name,
            ar.name AS artistFullName,
            al.name AS albumName,
            similarity(t.normalized_name, :normalizedQuery) AS score,
            CASE
                WHEN t.normalized_name = :normalizedQuery THEN 'EXACT'
                WHEN t.normalized_name LIKE :normalizedQuery || '%' THEN 'PREFIX'
                WHEN t.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 'CONTAINS'
                ELSE 'FUZZY'
            END AS matchType,
            ted.editorial_id AS editorialId
        FROM tracks t
        JOIN albums al ON al.id = t.album_id
        JOIN artists ar ON ar.id = al.artist_id
        LEFT JOIN track_editorials ted ON ted.track_id = t.id
        WHERE t.normalized_name = :normalizedQuery
           OR t.normalized_name LIKE :normalizedQuery || '%'
           OR t.normalized_name LIKE '%' || :normalizedQuery || '%'
           OR t.normalized_name % :normalizedQuery
        ORDER BY
            CASE
                WHEN t.normalized_name = :normalizedQuery THEN 0
                WHEN t.normalized_name LIKE :normalizedQuery || '%' THEN 1
                WHEN t.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 2
                ELSE 3
            END,
            similarity(t.normalized_name, :normalizedQuery) DESC
        LIMIT 20
        """, nativeQuery = true)
    List<CandidateRow> search(@Param("normalizedQuery") String normalizedQuery);
}

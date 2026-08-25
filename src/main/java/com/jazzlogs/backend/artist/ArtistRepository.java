package com.jazzlogs.backend.artist;

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

public interface ArtistRepository extends JpaRepository<Artist, UUID>, SavedItemResolver, CatalogEntityResolver {

    /**
     * Spotify identity is what POST /artists upserts on — an artist with this
     * spotifyArtistId already existing means "update it", not "create a duplicate".
     */
    Optional<Artist> findBySpotifyArtistId(String spotifyArtistId);

    @Override
    default Optional<Resolved> resolve(UUID entityId) {
        return findById(entityId).map(ArtistRepository::toResolved);
    }

    @Override
    default Map<UUID, Resolved> resolveBatch(List<UUID> entityIds) {
        return findAllById(entityIds).stream()
            .collect(Collectors.toMap(Artist::getId, ArtistRepository::toResolved));
    }

    private static Resolved toResolved(Artist artist) {
        return new Resolved(artist.getName(), artist.getImageUrl(), artist.getSpotifyUrl());
    }

    /**
     * EXACT/PREFIX/CONTAINS take priority over FUZZY (the {@code %} operator,
     * using pg_trgm's default 0.3 similarity threshold) — matchType and the
     * final ordering come out of the same {@code CASE} so they can never
     * disagree. LIKE's {@code %}/{@code _} aren't escaped in the raw query
     * text — a literal {@code %} or {@code _} the user typed can make
     * PREFIX/CONTAINS behave oddly, though FUZZY still catches it; not
     * handled here yet.
     */
    @Override
    @Query(value = """
        SELECT
            ar.id AS id,
            ar.name AS name,
            ar.name AS artistFullName,
            NULL::text AS albumName,
            similarity(ar.normalized_name, :normalizedQuery) AS score,
            CASE
                WHEN ar.normalized_name = :normalizedQuery THEN 'EXACT'
                WHEN ar.normalized_name LIKE :normalizedQuery || '%' THEN 'PREFIX'
                WHEN ar.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 'CONTAINS'
                ELSE 'FUZZY'
            END AS matchType,
            aed.editorial_id AS editorialId
        FROM artists ar
        LEFT JOIN artist_editorials aed ON aed.artist_id = ar.id
        WHERE ar.normalized_name = :normalizedQuery
           OR ar.normalized_name LIKE :normalizedQuery || '%'
           OR ar.normalized_name LIKE '%' || :normalizedQuery || '%'
           OR ar.normalized_name % :normalizedQuery
        ORDER BY
            CASE
                WHEN ar.normalized_name = :normalizedQuery THEN 0
                WHEN ar.normalized_name LIKE :normalizedQuery || '%' THEN 1
                WHEN ar.normalized_name LIKE '%' || :normalizedQuery || '%' THEN 2
                ELSE 3
            END,
            similarity(ar.normalized_name, :normalizedQuery) DESC
        LIMIT 20
        """, nativeQuery = true)
    List<CandidateRow> search(@Param("normalizedQuery") String normalizedQuery);
}

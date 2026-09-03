package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Backs the archive page's endpoints — see {@link EditorialSummary} for what the underlying view is. */
public interface EditorialSummaryRepository extends JpaRepository<EditorialSummary, UUID> {

    /**
     * {@code findFirst}, not {@code find}, defensively: {@code
     * idx_editorials_only_one_featured} (see V18) enforces at most one row
     * with {@code featurated = true} at the DB level, so this should never
     * actually find more than one — {@code findFirst} just means a second
     * row would be picked over, not an error, if that guarantee were ever
     * violated.
     *
     * @return the featurated editorial, or empty if none is set
     */
    Optional<EditorialSummary> findFirstByFeaturatedTrue();

    /**
     * Backs the Catalogue's free-form filter/search — the only caller that
     * needs both {@code type} and {@code pattern} to be genuinely optional
     * at once. A constructor expression, not {@code SELECT s} — this only
     * selects {@link CatalogueEditorialRow}'s columns, so Postgres never
     * evaluates {@code preview_text}'s per-row {@code LATERAL} block
     * lookup (the Catalogue grid doesn't render it) or {@code featurated}/
     * {@code release_year} for this query.
     *
     * @param type    restricts to one owner type, or {@code null} for every type
     * @param pattern a pre-built {@code "%...%"} string (see {@code
     *                EditorialService}), rather than {@code CONCAT('%',
     *                :q, '%')} inline — Hibernate 6 sometimes fails to
     *                infer a CONCAT bind parameter's type against Postgres
     *                and sends it as bytea, which {@code LOWER()} then
     *                rejects ({@code "function lower(bytea) does not exist"})
     */
    @Query("""
        SELECT new com.jazzlogs.backend.editorial.CatalogueEditorialRow(
            s.id, s.ownerType, s.ownerId, s.ownerName, s.ownerImageUrl,
            s.contextName, s.contextId, s.title, s.dek, s.byline,
            s.createdAt, s.logNumber, s.likeCount
        )
        FROM EditorialSummary s
        WHERE (:type IS NULL OR s.ownerType = :type)
          AND (:pattern IS NULL
               OR LOWER(s.title) LIKE :pattern
               OR LOWER(s.ownerName) LIKE :pattern)
        """)
    Page<CatalogueEditorialRow> searchLean(
        @Param("type") EditorialOwnerType type, @Param("pattern") String pattern, Pageable pageable
    );

    /**
     * Backs "Recently filed" — always ALBUM (hardcoded in the JPQL, not a
     * parameter: this endpoint never needs another type), newest-first.
     * {@code pageable} only supplies the cap (see {@code
     * EditorialService.RECENT_ALBUMS_LIMIT}) — a plain {@code List} return
     * type means Spring Data applies it as a {@code LIMIT} without the
     * extra {@code COUNT(*)} a {@code Page} would run.
     */
    @Query("""
        SELECT new com.jazzlogs.backend.editorial.RecentAlbumEditorialRow(
            s.id, s.ownerId, s.contextId, s.ownerImageUrl, s.title,
            s.ownerName, s.contextName, s.dek, s.byline, s.logNumber, s.likeCount
        )
        FROM EditorialSummary s
        WHERE s.ownerType = com.jazzlogs.backend.editorial.EditorialOwnerType.ALBUM
        ORDER BY s.createdAt DESC
        """)
    List<RecentAlbumEditorialRow> findRecentAlbums(Pageable pageable);

    /**
     * Backs "The Last Log" — the single most recently created album
     * editorial. Same WHERE/ORDER as {@link #findRecentAlbums}; {@code
     * pageable} is expected to cap this at one row (there's only ever one
     * "last" edition), a plain {@code List} rather than {@code Optional}
     * because Spring Data can't return {@code Optional} from a query capped
     * by {@code Pageable} alone.
     */
    @Query("""
        SELECT new com.jazzlogs.backend.editorial.LastLogEditorialRow(
            s.id, s.ownerId, s.title, s.contextName, s.dek, s.byline,
            s.releaseYear, s.createdAt, s.ownerImageUrl, s.likeCount
        )
        FROM EditorialSummary s
        WHERE s.ownerType = com.jazzlogs.backend.editorial.EditorialOwnerType.ALBUM
        ORDER BY s.createdAt DESC
        """)
    List<LastLogEditorialRow> findLatestAlbum(Pageable pageable);
}

package com.jazzlogs.backend.agent;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by {@code AlbumRepository}/{@code ArtistRepository}/{@code
 * TrackRepository} so {@link ResolveJazzlogsEntityTool} can dispatch by
 * {@code CatalogItemType} through a single {@code Map<CatalogItemType,
 * CatalogEntityResolver>} — same shape as {@code SavedItemResolver} elsewhere
 * in this codebase.
 */
public interface CatalogEntityResolver {

    /**
     * @param normalizedQuery must already be {@code Album.normalize()}'d (trim,
     *                        lowercase, collapse whitespace) — every implementor
     *                        searches its own {@code normalized_name} column,
     *                        populated the same way on write
     * @param limit           the most rows to return
     * @return up to {@code limit} rows, one per entity, already ordered by
     *         matchType priority (EXACT > PREFIX > CONTAINS > FUZZY) then
     *         score descending — callers shouldn't need to re-sort
     */
    List<CandidateRow> search(String normalizedQuery, int limit);

    interface CandidateRow {
        UUID getId();

        String getName();

        String getArtistFullName();

        /** Only meaningful for TRACK candidates — null for ALBUM/ARTIST rows. */
        String getAlbumName();

        Double getScore();

        String getMatchType();
    }
}

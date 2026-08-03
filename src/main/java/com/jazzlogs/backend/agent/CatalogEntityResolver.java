package com.jazzlogs.backend.agent;

import java.util.List;
import java.util.UUID;

// Implemented by AlbumRepository/ArtistRepository/TrackRepository so
// ResolveJazzlogsEntityTool can dispatch by CatalogItemType through a single
// Map<CatalogItemType, CatalogEntityResolver> — same shape as
// SavedItemResolver elsewhere in this codebase.
public interface CatalogEntityResolver {

    // normalizedQuery must already be Album.normalize()'d (trim, lowercase,
    // collapse whitespace) — every implementor searches its own
    // normalized_name column, populated the same way on write. Returns up to
    // 20 rows (the fuzzy shortlist), already ordered by matchType priority
    // (EXACT > PREFIX > CONTAINS > FUZZY) then score descending — callers
    // shouldn't need to re-sort, only dedupe/truncate.
    List<CandidateRow> search(String normalizedQuery);

    interface CandidateRow {
        UUID getId();

        String getName();

        String getArtistFullName();

        // Only meaningful for TRACK candidates — null for ALBUM/ARTIST rows.
        String getAlbumName();

        Double getScore();

        String getMatchType();

        // What EDITORIAL_CONTENT/EDITORIAL_SEARCH take directly as input, so
        // they never have to re-resolve which editorial subclass table owns
        // this entity. Nullable only as a data-integrity safety net — every
        // entity has an editorial in practice.
        UUID getEditorialId();
    }
}

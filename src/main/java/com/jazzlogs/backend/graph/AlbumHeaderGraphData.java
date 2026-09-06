package com.jazzlogs.backend.graph;

import java.util.List;

/**
 * Everything {@code AlbumHeaderDto} needs from Neo4j, fetched in one round
 * trip — see {@link GraphService#getAlbumHeaderGraphData}. Styles/moods/
 * contexts are plain display labels here, not {@link VocabularyTag} (label
 * + code) — the album header is read-only, never fed back into a PUT
 * .../tags/* replace the way {@code TrackTagsDto}'s codes are, so there's
 * nothing here that needs the code half.
 *
 * @param styles    style tag labels
 * @param moods     mood tag labels
 * @param contexts  context tag labels
 * @param personnel sidemen/personnel credited on the album
 */
public record AlbumHeaderGraphData(
    List<String> styles,
    List<String> moods,
    List<String> contexts,
    List<AlbumPersonnelEntry> personnel
) {
}

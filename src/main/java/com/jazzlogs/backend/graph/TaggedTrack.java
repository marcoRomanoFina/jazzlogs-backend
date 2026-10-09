package com.jazzlogs.backend.graph;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A track and the requested vocabulary codes it is actually tagged with —
 * see {@link GraphService#findTracksByTags}.
 *
 * @param trackId     the track's catalog id (the node's {@code id} property,
 *                    never Neo4j's own internal id)
 * @param matchedTags the matching codes, per dimension; a dimension with no
 *                    match is absent, and the map is empty when the search
 *                    asked for no codes at all (a pure album/artist scope)
 */
public record TaggedTrack(UUID trackId, Map<VocabularyDimension, List<String>> matchedTags) {
}

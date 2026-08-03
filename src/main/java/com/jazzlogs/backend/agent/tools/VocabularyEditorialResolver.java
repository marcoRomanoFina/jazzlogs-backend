package com.jazzlogs.backend.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.AlbumEditorialRepository;
import com.jazzlogs.backend.editorial.ArtistEditorialRepository;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.vocabulary.VocabularyFilterType;

// Steps 1+2 of EDITORIAL_SEARCH's vocabularyFilter prefilter, package-private
// on purpose: this is an implementation detail of EditorialSearchTool, never
// its own tool call — the model only ever sees vocabularyFilter as an input
// field and editorial blocks as output, never these intermediate entity/
// editorial lookups.
//   1. Neo4j: which entities carry this vocabulary tag (findEntities)
//   2. Postgres: which editorials those entities own (resolveEditorialIds)
@Component
class VocabularyEditorialResolver {

    private record EdgeSpec(String sourceLabel, String relationshipType, String targetLabel) {
    }

    // Neither the relationship name nor the source label is uniform across
    // entity types for every vocabulary type — Style is BELONGS_TO on Album
    // but HAS_STYLE on Artist; Instrument is FEATURES_INSTRUMENT on Track but
    // PLAYS_INSTRUMENT on Artist — this is the one place that mapping lives
    // (mirrors the write side in GraphService's addStyle/addTrackMood/etc.).
    private static final Map<VocabularyFilterType, Map<CatalogItemType, EdgeSpec>> EDGE_SPECS = Map.of(
        VocabularyFilterType.MOOD, Map.of(
            CatalogItemType.ALBUM, new EdgeSpec("Album", "EVOKES_MOOD", "Mood"),
            CatalogItemType.TRACK, new EdgeSpec("Track", "EVOKES_MOOD", "Mood")
        ),
        VocabularyFilterType.RHYTHM, Map.of(
            CatalogItemType.TRACK, new EdgeSpec("Track", "HAS_RHYTHM", "Rhythm")
        ),
        VocabularyFilterType.STYLE, Map.of(
            CatalogItemType.ALBUM, new EdgeSpec("Album", "BELONGS_TO", "Style"),
            CatalogItemType.ARTIST, new EdgeSpec("Artist", "HAS_STYLE", "Style")
        ),
        VocabularyFilterType.CONTEXT, Map.of(
            CatalogItemType.ALBUM, new EdgeSpec("Album", "PERFECT_FOR", "Context"),
            CatalogItemType.TRACK, new EdgeSpec("Track", "PERFECT_FOR", "Context"),
            CatalogItemType.ARTIST, new EdgeSpec("Artist", "PERFECT_FOR", "Context")
        ),
        VocabularyFilterType.INSTRUMENT, Map.of(
            CatalogItemType.TRACK, new EdgeSpec("Track", "FEATURES_INSTRUMENT", "Instrument"),
            CatalogItemType.ARTIST, new EdgeSpec("Artist", "PLAYS_INSTRUMENT", "Instrument")
        )
    );

    private final GraphService graphService;
    private final AlbumEditorialRepository albumEditorialRepository;
    private final TrackEditorialRepository trackEditorialRepository;
    private final ArtistEditorialRepository artistEditorialRepository;

    VocabularyEditorialResolver(
        GraphService graphService,
        AlbumEditorialRepository albumEditorialRepository,
        TrackEditorialRepository trackEditorialRepository,
        ArtistEditorialRepository artistEditorialRepository
    ) {
        this.graphService = graphService;
        this.albumEditorialRepository = albumEditorialRepository;
        this.trackEditorialRepository = trackEditorialRepository;
        this.artistEditorialRepository = artistEditorialRepository;
    }

    record EntityRef(CatalogItemType entityType, UUID entityId) {
    }

    // Step 1. entityType null means "every entity type this vocabulary type
    // applies to" — results from each are unioned. type/code are assumed
    // already validated against the right enum (see EditorialSearchTool);
    // this only validates that entityType, if given, is one this vocabulary
    // type actually supports (e.g. ARTIST is not valid for RHYTHM).
    List<EntityRef> findEntities(VocabularyFilterType type, String code, CatalogItemType entityType) {
        Map<CatalogItemType, EdgeSpec> specsForType = EDGE_SPECS.get(type);

        List<Map.Entry<CatalogItemType, EdgeSpec>> entries;
        if (entityType != null) {
            EdgeSpec spec = specsForType.get(entityType);
            if (spec == null) {
                throw new IllegalArgumentException(entityType + " is not a valid entityType for a " + type + " vocabularyFilter");
            }
            entries = List.of(Map.entry(entityType, spec));
        } else {
            entries = new ArrayList<>(specsForType.entrySet());
        }

        List<EntityRef> results = new ArrayList<>();
        for (Map.Entry<CatalogItemType, EdgeSpec> entry : entries) {
            EdgeSpec spec = entry.getValue();
            for (UUID entityId : graphService.findEntityIdsByVocabulary(spec.sourceLabel(), spec.relationshipType(), spec.targetLabel(), code)) {
                results.add(new EntityRef(entry.getKey(), entityId));
            }
        }
        return results;
    }

    // Step 2. Never called with an empty list — EditorialSearchTool
    // short-circuits to a no-results answer as soon as findEntities comes
    // back empty, without reaching this at all.
    Set<UUID> resolveEditorialIds(List<EntityRef> entities) {
        Map<CatalogItemType, List<UUID>> idsByType = entities.stream()
            .collect(Collectors.groupingBy(EntityRef::entityType, Collectors.mapping(EntityRef::entityId, Collectors.toList())));

        Set<UUID> editorialIds = new HashSet<>();
        List<UUID> albumIds = idsByType.get(CatalogItemType.ALBUM);
        if (albumIds != null) {
            editorialIds.addAll(albumEditorialRepository.findEditorialIdsByAlbumIdIn(albumIds));
        }
        List<UUID> trackIds = idsByType.get(CatalogItemType.TRACK);
        if (trackIds != null) {
            editorialIds.addAll(trackEditorialRepository.findEditorialIdsByTrackIdIn(trackIds));
        }
        List<UUID> artistIds = idsByType.get(CatalogItemType.ARTIST);
        if (artistIds != null) {
            editorialIds.addAll(artistEditorialRepository.findEditorialIdsByArtistIdIn(artistIds));
        }
        return editorialIds;
    }
}

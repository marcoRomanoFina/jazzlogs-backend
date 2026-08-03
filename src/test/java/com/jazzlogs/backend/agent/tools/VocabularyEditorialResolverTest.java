package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.AlbumEditorialRepository;
import com.jazzlogs.backend.editorial.ArtistEditorialRepository;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.vocabulary.VocabularyFilterType;

// Pure Mockito unit test — GraphService itself (real Cypher, real Neo4j) is
// out of scope here, same reasoning as everywhere else in this package: this
// only covers the mapping/dispatch logic that's specific to
// VocabularyEditorialResolver, treating GraphService and the editorial
// repositories as black boxes.
@ExtendWith(MockitoExtension.class)
class VocabularyEditorialResolverTest {

    @Mock
    private GraphService graphService;

    @Mock
    private AlbumEditorialRepository albumEditorialRepository;

    @Mock
    private TrackEditorialRepository trackEditorialRepository;

    @Mock
    private ArtistEditorialRepository artistEditorialRepository;

    private VocabularyEditorialResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VocabularyEditorialResolver(graphService, albumEditorialRepository, trackEditorialRepository, artistEditorialRepository);
    }

    @Test
    void entityTypeOmitted_unionsResultsAcrossEveryApplicableEntityType() {
        UUID albumId = UUID.randomUUID();
        UUID artistId = UUID.randomUUID();
        // STYLE applies to both ALBUM (BELONGS_TO) and ARTIST (HAS_STYLE) —
        // different relationship names, see VocabularyEditorialResolver.EDGE_SPECS.
        when(graphService.findEntityIdsByVocabulary("Album", "BELONGS_TO", "Style", "SWING")).thenReturn(List.of(albumId));
        when(graphService.findEntityIdsByVocabulary("Artist", "HAS_STYLE", "Style", "SWING")).thenReturn(List.of(artistId));

        List<VocabularyEditorialResolver.EntityRef> entities = resolver.findEntities(VocabularyFilterType.STYLE, "SWING", null);

        assertThat(entities).containsExactlyInAnyOrder(
            new VocabularyEditorialResolver.EntityRef(CatalogItemType.ALBUM, albumId),
            new VocabularyEditorialResolver.EntityRef(CatalogItemType.ARTIST, artistId)
        );
    }

    @Test
    void entityTypeSpecified_onlyQueriesThatOneEdge() {
        UUID albumId = UUID.randomUUID();
        when(graphService.findEntityIdsByVocabulary("Album", "BELONGS_TO", "Style", "SWING")).thenReturn(List.of(albumId));

        List<VocabularyEditorialResolver.EntityRef> entities = resolver.findEntities(VocabularyFilterType.STYLE, "SWING", CatalogItemType.ALBUM);

        assertThat(entities).containsExactly(new VocabularyEditorialResolver.EntityRef(CatalogItemType.ALBUM, albumId));
        verify(graphService, never()).findEntityIdsByVocabulary("Artist", "HAS_STYLE", "Style", "SWING");
    }

    @Test
    void entityTypeNotApplicableToVocabularyType_throws() {
        // RHYTHM only applies to TRACK — ARTIST isn't a valid entityType for it.
        assertThatThrownBy(() -> resolver.findEntities(VocabularyFilterType.RHYTHM, "SWUNG", CatalogItemType.ARTIST))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveEditorialIds_groupsByEntityTypeAndDispatchesToTheRightRepository() {
        UUID albumEntityId = UUID.randomUUID();
        UUID trackEntityId = UUID.randomUUID();
        UUID artistEntityId = UUID.randomUUID();
        UUID albumEditorialId = UUID.randomUUID();
        UUID trackEditorialId = UUID.randomUUID();
        UUID artistEditorialId = UUID.randomUUID();

        when(albumEditorialRepository.findEditorialIdsByAlbumIdIn(List.of(albumEntityId))).thenReturn(List.of(albumEditorialId));
        when(trackEditorialRepository.findEditorialIdsByTrackIdIn(List.of(trackEntityId))).thenReturn(List.of(trackEditorialId));
        when(artistEditorialRepository.findEditorialIdsByArtistIdIn(List.of(artistEntityId))).thenReturn(List.of(artistEditorialId));

        List<VocabularyEditorialResolver.EntityRef> entities = List.of(
            new VocabularyEditorialResolver.EntityRef(CatalogItemType.ALBUM, albumEntityId),
            new VocabularyEditorialResolver.EntityRef(CatalogItemType.TRACK, trackEntityId),
            new VocabularyEditorialResolver.EntityRef(CatalogItemType.ARTIST, artistEntityId)
        );

        assertThat(resolver.resolveEditorialIds(entities))
            .containsExactlyInAnyOrder(albumEditorialId, trackEditorialId, artistEditorialId);
    }

    @Test
    void resolveEditorialIds_onlyQueriesRepositoriesForEntityTypesActuallyPresent() {
        UUID albumEntityId = UUID.randomUUID();
        when(albumEditorialRepository.findEditorialIdsByAlbumIdIn(List.of(albumEntityId))).thenReturn(List.of(UUID.randomUUID()));

        resolver.resolveEditorialIds(List.of(new VocabularyEditorialResolver.EntityRef(CatalogItemType.ALBUM, albumEntityId)));

        verify(trackEditorialRepository, never()).findEditorialIdsByTrackIdIn(anyList());
        verify(artistEditorialRepository, never()).findEditorialIdsByArtistIdIn(anyList());
    }
}

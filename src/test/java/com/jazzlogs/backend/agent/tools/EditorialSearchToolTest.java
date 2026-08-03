package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.vocabulary.VocabularyFilterType;

// The repository's native pg_vector query is what actually establishes score
// ordering (see EditorialBlockRepository.semanticSearch) — not exercised
// here: this is a pure Mockito unit test, no Spring context, no real DB hit
// regardless of profile. VocabularyEditorialResolver (steps 1+2 of
// vocabularyFilter) is mocked too — its own graph/union logic is covered in
// VocabularyEditorialResolverTest. These tests cover what the tool itself is
// responsible for: input validation, embedding the query text, the
// editorialIds/vocabularyFilter intersection and short-circuit, entityType/
// entityName resolution (including the TrackEditorial backfill), and output
// shape.
@ExtendWith(MockitoExtension.class)
class EditorialSearchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private EditorialBlockRepository editorialBlockRepository;

    @Mock
    private TrackEditorialRepository trackEditorialRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VocabularyEditorialResolver vocabularyResolver;

    private EditorialSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new EditorialSearchTool(editorialBlockRepository, trackEditorialRepository, embeddingService, vocabularyResolver);
    }

    @Test
    void blankQueryText_throws() {
        ToolCallRequest call = callWith("{\"queryText\":\"\"}");

        assertThatThrownBy(() -> tool.execute(call)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedEditorialIds_throws() {
        ToolCallRequest call = callWith("{\"queryText\":\"a jazz story\",\"editorialIds\":[\"not-a-uuid\"]}");

        assertThatThrownBy(() -> tool.execute(call)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCategory_throws() {
        ToolCallRequest call = callWith("{\"queryText\":\"a jazz story\",\"category\":\"NOT_REAL\"}");

        assertThatThrownBy(() -> tool.execute(call)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidEntityType_throws() {
        ToolCallRequest call = callWith("{\"queryText\":\"a jazz story\",\"entityType\":\"PLAYLIST\"}");

        assertThatThrownBy(() -> tool.execute(call)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidVocabularyFilterType_throws() {
        ToolCallRequest call = callWith("{\"queryText\":\"a jazz story\",\"vocabularyFilter\":{\"type\":\"NOT_REAL\",\"code\":\"SWING\"}}");

        assertThatThrownBy(() -> tool.execute(call)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vocabularyResolver);
    }

    @Test
    void invalidVocabularyFilterCode_throws() {
        ToolCallRequest call = callWith("{\"queryText\":\"a jazz story\",\"vocabularyFilter\":{\"type\":\"STYLE\",\"code\":\"NOT_A_STYLE\"}}");

        assertThatThrownBy(() -> tool.execute(call)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vocabularyResolver);
    }

    @Test
    void embedsQueryText_andPassesAPgvectorLiteralThrough() {
        when(embeddingService.embed("a jazz story")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(editorialBlockRepository.semanticSearch(any(), isNull(), eq(5))).thenReturn(List.of());

        tool.execute(callWith("{\"queryText\":\"a jazz story\"}"));

        ArgumentCaptor<String> embeddingCaptor = ArgumentCaptor.forClass(String.class);
        verify(editorialBlockRepository).semanticSearch(embeddingCaptor.capture(), isNull(), eq(5));
        assertThat(embeddingCaptor.getValue()).startsWith("[").endsWith("]").contains(",");
    }

    @Test
    void editorialIdsAndCategory_arePassedThroughToTheScopedQuery() {
        UUID editorialId = UUID.randomUUID();
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f, 0.2f});
        when(editorialBlockRepository.semanticSearchScopedToEditorials(any(), eq(List.of(editorialId)), eq("ANECDOTE"), eq(5)))
            .thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith(
            "{\"queryText\":\"a jazz story\",\"editorialIds\":[\"" + editorialId + "\"],\"category\":\"ANECDOTE\"}"
        ));

        verify(editorialBlockRepository).semanticSearchScopedToEditorials(any(), eq(List.of(editorialId)), eq("ANECDOTE"), eq(5));
        JsonNode metadata = readMetadata(result);
        assertThat(metadata.get("editorialIds").get(0).asText()).isEqualTo(editorialId.toString());
        assertThat(metadata.get("category").asText()).isEqualTo("ANECDOTE");
    }

    @Test
    void vocabularyFilterWithNoGraphMatches_shortCircuitsWithoutQueryingPostgresOrPgvector() throws Exception {
        when(vocabularyResolver.findEntities(VocabularyFilterType.MOOD, "GROOVY", null)).thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith(
            "{\"queryText\":\"something groovy\",\"vocabularyFilter\":{\"type\":\"MOOD\",\"code\":\"GROOVY\"}}"
        ));

        verify(vocabularyResolver, never()).resolveEditorialIds(any());
        verifyNoInteractions(embeddingService, editorialBlockRepository);
        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("No editorial blocks found");
        assertThat(json.get("metadata").get("results")).isEmpty();
        assertThat(json.get("metadata").get("resolvedEntityCount").asInt()).isEqualTo(0);
    }

    @Test
    void vocabularyFilterResolvesEntities_butNoneHaveEditorials_alsoShortCircuits() throws Exception {
        VocabularyEditorialResolver.EntityRef entityRef = new VocabularyEditorialResolver.EntityRef(CatalogItemType.ALBUM, UUID.randomUUID());
        when(vocabularyResolver.findEntities(VocabularyFilterType.STYLE, "SWING", null)).thenReturn(List.of(entityRef));
        when(vocabularyResolver.resolveEditorialIds(List.of(entityRef))).thenReturn(Set.of());

        ToolExecutionResult result = tool.execute(callWith(
            "{\"queryText\":\"swinging story\",\"vocabularyFilter\":{\"type\":\"STYLE\",\"code\":\"SWING\"}}"
        ));

        verifyNoInteractions(embeddingService, editorialBlockRepository);
        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("metadata").get("results")).isEmpty();
        assertThat(json.get("metadata").get("resolvedEntityCount").asInt()).isEqualTo(1);
    }

    @Test
    void editorialIdsAndVocabularyFilter_combinedWithIntersection() {
        UUID keep = UUID.randomUUID();
        UUID onlyInInput = UUID.randomUUID();
        UUID onlyInVocabulary = UUID.randomUUID();

        VocabularyEditorialResolver.EntityRef entityRef = new VocabularyEditorialResolver.EntityRef(CatalogItemType.ALBUM, UUID.randomUUID());
        when(vocabularyResolver.findEntities(VocabularyFilterType.STYLE, "SWING", null)).thenReturn(List.of(entityRef));
        when(vocabularyResolver.resolveEditorialIds(List.of(entityRef))).thenReturn(Set.of(keep, onlyInVocabulary));

        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearchScopedToEditorials(any(), eq(List.of(keep)), isNull(), eq(5)))
            .thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith(
            "{\"queryText\":\"swinging story\",\"editorialIds\":[\"" + keep + "\",\"" + onlyInInput + "\"],"
                + "\"vocabularyFilter\":{\"type\":\"STYLE\",\"code\":\"SWING\"}}"
        ));

        verify(editorialBlockRepository).semanticSearchScopedToEditorials(any(), eq(List.of(keep)), isNull(), eq(5));
        JsonNode metadata = readMetadata(result);
        assertThat(metadata.get("editorialIds")).hasSize(1);
        assertThat(metadata.get("editorialIds").get(0).asText()).isEqualTo(keep.toString());
    }

    @Test
    void albumBlock_resolvesEntityTypeAndNameFromMetadata_noExtraQuery() throws Exception {
        UUID editorialId = UUID.randomUUID();
        String metadataJson = "{\"editorialType\":\"AlbumEditorial\",\"albumName\":\"Kind of Blue\",\"artistName\":\"Miles Davis\"}";
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        // row() built as its own statement, not inline in .thenReturn(...) —
        // see AgentOrchestratorTest/ResolveJazzlogsEntityToolTest for why
        // that nesting trips Mockito's "unfinished stubbing" detection.
        EditorialBlockRepository.SemanticSearchRow row = row(editorialId, "AlbumEditorial", metadataJson, "text", 0.9);
        when(editorialBlockRepository.semanticSearch(any(), isNull(), eq(5))).thenReturn(List.of(row));

        ToolExecutionResult result = tool.execute(callWith("{\"queryText\":\"mellow modal jazz\"}"));

        JsonNode result0 = readMetadata(result).get("results").get(0);
        assertThat(result0.get("entityType").asText()).isEqualTo("ALBUM");
        assertThat(result0.get("entityName").asText()).isEqualTo("Kind of Blue");
        verify(trackEditorialRepository, never()).findNamesByEditorialIdIn(any());
    }

    @Test
    void artistBlock_resolvesEntityTypeAndNameFromMetadata() throws Exception {
        UUID editorialId = UUID.randomUUID();
        String metadataJson = "{\"editorialType\":\"ArtistEditorial\",\"artistName\":\"Miles Davis\"}";
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        EditorialBlockRepository.SemanticSearchRow row = row(editorialId, "ArtistEditorial", metadataJson, "text", 0.8);
        when(editorialBlockRepository.semanticSearch(any(), isNull(), eq(5))).thenReturn(List.of(row));

        ToolExecutionResult result = tool.execute(callWith("{\"queryText\":\"the man himself\"}"));

        JsonNode result0 = readMetadata(result).get("results").get(0);
        assertThat(result0.get("entityType").asText()).isEqualTo("ARTIST");
        assertThat(result0.get("entityName").asText()).isEqualTo("Miles Davis");
    }

    @Test
    void trackBlock_backfillsNameViaTrackEditorialRepository_whenMetadataLacksIt() throws Exception {
        UUID editorialId = UUID.randomUUID();
        String metadataJson = "{\"editorialType\":\"TrackEditorial\"}";
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        EditorialBlockRepository.SemanticSearchRow row = row(editorialId, "TrackEditorial", metadataJson, "text", 0.7);
        when(editorialBlockRepository.semanticSearch(any(), isNull(), eq(5))).thenReturn(List.of(row));

        TrackEditorialRepository.NameRow nameRow = mock(TrackEditorialRepository.NameRow.class);
        when(nameRow.getEditorialId()).thenReturn(editorialId);
        when(nameRow.getName()).thenReturn("So What");
        when(trackEditorialRepository.findNamesByEditorialIdIn(anyList())).thenReturn(List.of(nameRow));

        ToolExecutionResult result = tool.execute(callWith("{\"queryText\":\"iconic modal track\"}"));

        JsonNode result0 = readMetadata(result).get("results").get(0);
        assertThat(result0.get("entityType").asText()).isEqualTo("TRACK");
        assertThat(result0.get("entityName").asText()).isEqualTo("So What");
        verify(trackEditorialRepository).findNamesByEditorialIdIn(List.of(editorialId));
    }

    @Test
    void malformedMetadata_degradesToNullEntityInfo_insteadOfFailingTheSearch() throws Exception {
        UUID editorialId = UUID.randomUUID();
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        EditorialBlockRepository.SemanticSearchRow row = row(editorialId, null, "not valid json", "text", 0.5);
        when(editorialBlockRepository.semanticSearch(any(), isNull(), eq(5))).thenReturn(List.of(row));

        ToolExecutionResult result = tool.execute(callWith("{\"queryText\":\"something obscure\"}"));

        JsonNode result0 = readMetadata(result).get("results").get(0);
        assertThat(result0.get("entityType").isNull()).isTrue();
        assertThat(result0.get("entityName").isNull()).isTrue();
        assertThat(result0.get("text").asText()).isEqualTo("text");
    }

    @Test
    void noResults_hasPlainTextContent() throws Exception {
        when(embeddingService.embed(any())).thenReturn(new float[] {0.1f});
        when(editorialBlockRepository.semanticSearch(any(), isNull(), eq(5))).thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith("{\"queryText\":\"nonexistent theme\"}"));

        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("No editorial blocks found");
        assertThat(json.get("metadata").get("results")).isEmpty();
    }

    private JsonNode readMetadata(ToolExecutionResult result) {
        try {
            return JSON.readTree(result.payload()).get("metadata");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", EditorialSearchTool.NAME, argumentsJson);
    }

    private static EditorialBlockRepository.SemanticSearchRow row(
        UUID editorialId, String editorialType, String metadataJson, String text, double score
    ) {
        EditorialBlockRepository.SemanticSearchRow row = mock(EditorialBlockRepository.SemanticSearchRow.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getEditorialId()).thenReturn(editorialId);
        when(row.getContentCategory()).thenReturn("ANECDOTE");
        when(row.getText()).thenReturn(text);
        when(row.getEmbeddingMetadataJson()).thenReturn(metadataJson);
        when(row.getScore()).thenReturn(score);
        return row;
    }
}

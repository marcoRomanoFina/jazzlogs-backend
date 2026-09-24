package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.graph.GraphCandidate;
import com.jazzlogs.backend.graph.GraphFilterFilters;
import com.jazzlogs.backend.graph.GraphFilterResult;
import com.jazzlogs.backend.graph.GraphFilterService;
import com.jazzlogs.backend.graph.MatchedDimension;
import com.jazzlogs.backend.graph.VocabularyDimension;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Pure Mockito unit test, no Spring context — GraphFilterService is mocked,
// so this only covers what the tool itself is responsible for: turning the
// model's raw JSON args into typed GraphFilterFilters (rejecting unknown
// vocabulary codes and a malformed albumId/artistId along the way),
// threading userId through, and shaping the JSON output. TRACK is the only
// entity type this searches now — there is no entityType arg anymore.
@ExtendWith(MockitoExtension.class)
class GraphFilterToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private GraphFilterService graphFilterService;

    private GraphFilterTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphFilterTool(graphFilterService, new JsonMapper());
    }

    @Test
    void unknownStyleCode_throws() {
        ToolCallRequest call = callWith("{\"styles\":[\"NOT_REAL\"]}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedAlbumId_throws() {
        ToolCallRequest call = callWith("{\"styles\":[\"BEBOP\"],\"albumId\":\"not-a-uuid\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedJson_throws() {
        ToolCallRequest call = callWith("not json");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesArgs_andDelegatesToServiceWithUserId() {
        when(graphFilterService.filter(any(), eq(USER_ID))).thenReturn(new GraphFilterResult(List.of()));
        ToolCallRequest call = callWith(
            "{\"styles\":[\"BEBOP\"],\"moods\":[\"MELANCHOLIC\"],"
                + "\"excludeListened\":false,\"excludeAlreadyRated\":false,\"topK\":5}"
        );

        tool.execute(call, USER_ID);

        ArgumentCaptor<GraphFilterFilters> captor = ArgumentCaptor.forClass(GraphFilterFilters.class);
        verify(graphFilterService).filter(captor.capture(), eq(USER_ID));
        GraphFilterFilters filters = captor.getValue();
        assertThat(filters.styles()).containsExactly(StyleVocabulary.BEBOP);
        assertThat(filters.moods()).containsExactly(MoodVocabulary.MELANCHOLIC);
        assertThat(filters.excludeListened()).isFalse();
        assertThat(filters.excludeAlreadyRated()).isFalse();
        assertThat(filters.topK()).isEqualTo(5);
        assertThat(filters.albumId()).isNull();
        assertThat(filters.artistId()).isNull();
    }

    @Test
    void albumAndArtistId_areParsedAsUuids() {
        when(graphFilterService.filter(any(), eq(USER_ID))).thenReturn(new GraphFilterResult(List.of()));
        UUID albumId = UUID.randomUUID();
        UUID artistId = UUID.randomUUID();
        ToolCallRequest call = callWith(
            "{\"albumId\":\"" + albumId + "\",\"artistId\":\"" + artistId + "\"}"
        );

        tool.execute(call, USER_ID);

        ArgumentCaptor<GraphFilterFilters> captor = ArgumentCaptor.forClass(GraphFilterFilters.class);
        verify(graphFilterService).filter(captor.capture(), eq(USER_ID));
        GraphFilterFilters filters = captor.getValue();
        assertThat(filters.albumId()).isEqualTo(albumId);
        assertThat(filters.artistId()).isEqualTo(artistId);
    }

    @Test
    void omittedFilters_areParsedAsEmptyLists_notNullElements() {
        when(graphFilterService.filter(any(), eq(USER_ID))).thenReturn(new GraphFilterResult(List.of()));
        ToolCallRequest call = callWith("{\"styles\":[\"BEBOP\"]}");

        tool.execute(call, USER_ID);

        ArgumentCaptor<GraphFilterFilters> captor = ArgumentCaptor.forClass(GraphFilterFilters.class);
        verify(graphFilterService).filter(captor.capture(), eq(USER_ID));
        GraphFilterFilters filters = captor.getValue();
        assertThat(filters.rhythms()).isEmpty();
        assertThat(filters.moods()).isEmpty();
        assertThat(filters.contexts()).isEmpty();
        assertThat(filters.instruments()).isEmpty();
        assertThat(filters.excludeListened()).isNull();
        assertThat(filters.excludeAlreadyRated()).isNull();
        assertThat(filters.topK()).isNull();
    }

    @Test
    void resultCandidates_areSerializedIntoMetadata() throws Exception {
        UUID candidateId = UUID.randomUUID();
        List<MatchedDimension> matches = List.of(
            new MatchedDimension(VocabularyDimension.STYLE, "BEBOP"),
            new MatchedDimension(VocabularyDimension.MOOD, "MELANCHOLIC")
        );
        when(graphFilterService.filter(any(), eq(USER_ID))).thenReturn(
            new GraphFilterResult(List.of(new GraphCandidate(CatalogItemType.TRACK, candidateId, "So What", matches)))
        );
        ToolCallRequest call = callWith("{\"styles\":[\"BEBOP\"]}");

        ToolExecutionResult result = tool.execute(call, USER_ID);

        JsonNode candidate = JSON.readTree(result.payload()).get("metadata").get("candidates").get(0);
        assertThat(candidate.get("entityType").asText()).isEqualTo("TRACK");
        assertThat(candidate.get("entityId").asText()).isEqualTo(candidateId.toString());
        assertThat(candidate.get("entityName").asText()).isEqualTo("So What");
        assertThat(candidate.get("matchedDimensions")).hasSize(2);
        assertThat(candidate.get("matchedDimensions").get(0).get("dimension").asText()).isEqualTo("STYLE");
        assertThat(candidate.get("matchedDimensions").get(0).get("code").asText()).isEqualTo("BEBOP");
        assertThat(result.success()).isTrue();
    }

    @Test
    void noCandidates_hasPlainTextContent() throws Exception {
        when(graphFilterService.filter(any(), eq(USER_ID))).thenReturn(new GraphFilterResult(List.of()));
        ToolCallRequest call = callWith("{\"styles\":[\"BEBOP\"]}");

        ToolExecutionResult result = tool.execute(call, USER_ID);

        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("No graph candidates matched");
        assertThat(json.get("metadata").get("candidates")).isEmpty();
    }

    @Test
    void schema_isNamedCorrectly() {
        assertThat(tool.name()).isEqualTo(GraphFilterTool.NAME);
        assertThat(tool.toFunctionTool().name()).isEqualTo(GraphFilterTool.NAME);
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", GraphFilterTool.NAME, argumentsJson);
    }
}

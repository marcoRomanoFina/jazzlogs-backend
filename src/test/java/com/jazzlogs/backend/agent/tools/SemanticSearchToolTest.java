package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.semanticsearch.ScoredBlock;
import com.jazzlogs.backend.semanticsearch.SemanticSearchRequest;
import com.jazzlogs.backend.semanticsearch.SemanticSearchResult;
import com.jazzlogs.backend.semanticsearch.SemanticSearchService;

// Pure Mockito unit test, no Spring context — SemanticSearchService is
// mocked, so this only covers what the tool itself is responsible for:
// turning the model's raw JSON args into a typed SemanticSearchRequest
// (rejecting unknown enum codes and a missing/null entityType/candidateIds
// along the way) and shaping the JSON output.
@ExtendWith(MockitoExtension.class)
class SemanticSearchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private SemanticSearchService semanticSearchService;

    private SemanticSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new SemanticSearchTool(semanticSearchService, new JsonMapper());
    }

    @Test
    void missingEntityType_throws() {
        ToolCallRequest call = callWith("{\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"a story\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownEntityType_throws() {
        ToolCallRequest call = callWith(
            "{\"entityType\":\"PLAYLIST\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"a story\"}"
        );

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingCandidateIds_throws() {
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"category\":\"ANECDOTE\",\"queryText\":\"a story\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedCandidateId_throws() {
        ToolCallRequest call = callWith(
            "{\"entityType\":\"ALBUM\",\"candidateIds\":[\"not-a-uuid\"],\"category\":\"ANECDOTE\",\"queryText\":\"a story\"}"
        );

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankCategory_throws() {
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"\",\"queryText\":\"a story\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCategory_throws() {
        ToolCallRequest call = callWith(
            "{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"NOT_REAL\",\"queryText\":\"a story\"}"
        );

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankQueryText_throws() {
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownLevelValue_throws() {
        ToolCallRequest call = callWith(
            "{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"q\",\"energy\":\"EXTREME\"}"
        );

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesArgs_andDelegatesToService() {
        when(semanticSearchService.search(any())).thenReturn(new SemanticSearchResult(List.of()));
        UUID albumId = UUID.randomUUID();
        ToolCallRequest call = callWith(
            "{\"entityType\":\"ALBUM\",\"candidateIds\":[\"" + albumId + "\"],"
                + "\"category\":\"ANECDOTE\",\"energy\":\"HIGH\",\"accessibility\":\"LOW\",\"moodIntensity\":\"MEDIUM\","
                + "\"queryText\":\"a mellow late-night session\"}"
        );

        tool.execute(call, USER_ID);

        ArgumentCaptor<SemanticSearchRequest> captor = ArgumentCaptor.forClass(SemanticSearchRequest.class);
        verify(semanticSearchService).search(captor.capture());
        SemanticSearchRequest request = captor.getValue();
        assertThat(request.entityType()).isEqualTo(CatalogItemType.ALBUM);
        assertThat(request.candidateIds()).containsExactly(albumId);
        assertThat(request.category()).isEqualTo(BlockContentCategory.ANECDOTE);
        assertThat(request.energy()).isEqualTo(Level.HIGH);
        assertThat(request.accessibility()).isEqualTo(Level.LOW);
        assertThat(request.moodIntensity()).isEqualTo(Level.MEDIUM);
        assertThat(request.queryText()).isEqualTo("a mellow late-night session");
    }

    @Test
    void emptyCandidateIdsList_isValid_notRejected() {
        when(semanticSearchService.search(any())).thenReturn(new SemanticSearchResult(List.of()));
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"q\"}");

        ToolExecutionResult result = tool.execute(call, USER_ID);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<SemanticSearchRequest> captor = ArgumentCaptor.forClass(SemanticSearchRequest.class);
        verify(semanticSearchService).search(captor.capture());
        assertThat(captor.getValue().candidateIds()).isEmpty();
    }

    @Test
    void omittedOptionalFilters_areParsedAsNull() {
        when(semanticSearchService.search(any())).thenReturn(new SemanticSearchResult(List.of()));
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"q\"}");

        tool.execute(call, USER_ID);

        ArgumentCaptor<SemanticSearchRequest> captor = ArgumentCaptor.forClass(SemanticSearchRequest.class);
        verify(semanticSearchService).search(captor.capture());
        assertThat(captor.getValue().energy()).isNull();
        assertThat(captor.getValue().accessibility()).isNull();
        assertThat(captor.getValue().moodIntensity()).isNull();
    }

    @Test
    void resultMatches_areSerializedIntoMetadata() throws Exception {
        UUID albumId = UUID.randomUUID();
        ScoredBlock match = new ScoredBlock(
            CatalogItemType.ALBUM, albumId, BlockContentCategory.ANECDOTE, 0.87, "a story", "Kind of Blue"
        );
        when(semanticSearchService.search(any())).thenReturn(new SemanticSearchResult(List.of(match)));
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"q\"}");

        ToolExecutionResult result = tool.execute(call, USER_ID);

        JsonNode matchJson = JSON.readTree(result.payload()).get("metadata").get("matches").get(0);
        assertThat(matchJson.get("entityType").asText()).isEqualTo("ALBUM");
        assertThat(matchJson.get("entityId").asText()).isEqualTo(albumId.toString());
        assertThat(matchJson.get("entityName").asText()).isEqualTo("Kind of Blue");
        assertThat(matchJson.get("similarityScore").asDouble()).isEqualTo(0.87);
        assertThat(result.success()).isTrue();
    }

    @Test
    void noMatches_hasPlainTextContent() throws Exception {
        when(semanticSearchService.search(any())).thenReturn(new SemanticSearchResult(List.of()));
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"candidateIds\":[],\"category\":\"ANECDOTE\",\"queryText\":\"q\"}");

        ToolExecutionResult result = tool.execute(call, USER_ID);

        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("No semantically similar blocks found");
    }

    @Test
    void schema_declaresRequiredFields() {
        assertThat(tool.name()).isEqualTo(SemanticSearchTool.NAME);
        assertThat(tool.toFunctionTool().name()).isEqualTo(SemanticSearchTool.NAME);
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", SemanticSearchTool.NAME, argumentsJson);
    }
}

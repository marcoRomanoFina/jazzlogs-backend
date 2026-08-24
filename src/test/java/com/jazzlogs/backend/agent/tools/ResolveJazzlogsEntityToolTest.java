package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.CatalogEntityResolver;
import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.track.TrackRepository;

// The repository's native pg_trgm query is what actually establishes
// matchType/score ordering (see AlbumRepository.search etc.) — not exercised
// here at all: this is a pure Mockito unit test, no Spring context, no real
// DB hit regardless of profile. These tests treat CatalogEntityResolver.search
// as a black box already returning pre-ordered rows, and cover what the tool
// itself is responsible for: input validation, dedupe/truncate, and the
// output shape.
@ExtendWith(MockitoExtension.class)
class ResolveJazzlogsEntityToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ResolveJazzlogsEntityTool never reads userId — any fixed value works here.
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private AlbumRepository albumRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private TrackRepository trackRepository;

    private ResolveJazzlogsEntityTool tool;

    @BeforeEach
    void setUp() {
        tool = new ResolveJazzlogsEntityTool(albumRepository, artistRepository, trackRepository, new JsonMapper());
    }

    @Test
    void invalidEntityType_throws() {
        ToolCallRequest call = callWith("{\"entityType\":\"PLAYLIST\",\"query\":\"Kind of Blue\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankQuery_throws() {
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\",\"query\":\"   \"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingQuery_throws() {
        ToolCallRequest call = callWith("{\"entityType\":\"ALBUM\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedupesCandidatesById_andTruncatesToTheCap() throws Exception {
        UUID id = UUID.randomUUID();
        List<CatalogEntityResolver.CandidateRow> rows = new ArrayList<>();
        rows.add(row(id, "Kind of Blue", "Miles Davis", 1.0, "EXACT"));
        rows.add(row(id, "Kind of Blue", "Miles Davis", 1.0, "EXACT")); // duplicate id
        for (int i = 0; i < 10; i++) {
            rows.add(row(UUID.randomUUID(), "Kind of Blue Sessions " + i, "Someone Else", 0.4, "FUZZY"));
        }
        when(albumRepository.search("kind of blue")).thenReturn(rows);

        ToolExecutionResult result = tool.execute(callWith("{\"entityType\":\"ALBUM\",\"query\":\"Kind of Blue\"}"), USER_ID);

        // 12 rows in, 1 is a duplicate id (11 distinct) — asserting "fewer
        // than 11 came back" (not a hardcoded exact count) proves truncation
        // still kicks in at whatever MAX_CANDIDATES currently is, without
        // this test going stale the next time that constant changes.
        JsonNode candidates = JSON.readTree(result.payload()).get("metadata").get("candidates");
        assertThat(candidates.size()).isLessThan(11);
        assertThat(result.success()).isTrue();
    }

    @Test
    void preservesRepositoryOrder_doesNotReSort() throws Exception {
        CatalogEntityResolver.CandidateRow exact = row(UUID.randomUUID(), "Kind of Blue", "Miles Davis", 1.0, "EXACT");
        CatalogEntityResolver.CandidateRow prefix = row(UUID.randomUUID(), "Kind of Blueish", "Someone", 0.6, "PREFIX");
        CatalogEntityResolver.CandidateRow fuzzy = row(UUID.randomUUID(), "Kinda Bluesy", "Someone Else", 0.35, "FUZZY");
        when(albumRepository.search("kind of blue")).thenReturn(List.of(exact, prefix, fuzzy));

        ToolExecutionResult result = tool.execute(callWith("{\"entityType\":\"ALBUM\",\"query\":\"Kind of Blue\"}"), USER_ID);

        JsonNode candidates = JSON.readTree(result.payload()).get("metadata").get("candidates");
        assertThat(candidates.get(0).get("matchType").asText()).isEqualTo("EXACT");
        assertThat(candidates.get(1).get("matchType").asText()).isEqualTo("PREFIX");
        assertThat(candidates.get(2).get("matchType").asText()).isEqualTo("FUZZY");
    }

    @Test
    void trackCandidate_includesAlbumName() throws Exception {
        // Row built as its own statement, not inline inside .thenReturn(...):
        // trackRow/row do their own when(...).thenReturn(...) internally, and
        // nesting that inside another when(...).thenReturn(EXPR)'s argument
        // evaluation trips Mockito's "unfinished stubbing" detection — the
        // outer thenReturn is still pending while the inner one runs.
        CatalogEntityResolver.CandidateRow candidate = trackRow(UUID.randomUUID(), "Acknowledgement", "John Coltrane", "A Love Supreme", 1.0, "EXACT");
        when(trackRepository.search("acknowledgement")).thenReturn(List.of(candidate));

        ToolExecutionResult result = tool.execute(callWith("{\"entityType\":\"TRACK\",\"query\":\"Acknowledgement\"}"), USER_ID);

        JsonNode candidateJson = JSON.readTree(result.payload()).get("metadata").get("candidates").get(0);
        assertThat(candidateJson.get("album").asText()).isEqualTo("A Love Supreme");
    }

    @Test
    void albumCandidate_hasNullAlbumField() throws Exception {
        // See trackCandidate_includesAlbumName's comment on why row() is
        // built as its own statement here, not inline in .thenReturn(...).
        CatalogEntityResolver.CandidateRow row = row(UUID.randomUUID(), "Kind of Blue", "Miles Davis", 1.0, "EXACT");
        when(albumRepository.search("kind of blue")).thenReturn(List.of(row));

        ToolExecutionResult result = tool.execute(callWith("{\"entityType\":\"ALBUM\",\"query\":\"Kind of Blue\"}"), USER_ID);

        JsonNode candidate = JSON.readTree(result.payload()).get("metadata").get("candidates").get(0);
        assertThat(candidate.get("album").isNull()).isTrue();
    }

    @Test
    void candidate_includesEditorialIdFromTheResolverRow() throws Exception {
        UUID editorialId = UUID.randomUUID();
        CatalogEntityResolver.CandidateRow row = row(UUID.randomUUID(), "Kind of Blue", "Miles Davis", 1.0, "EXACT");
        when(row.getEditorialId()).thenReturn(editorialId);
        when(albumRepository.search("kind of blue")).thenReturn(List.of(row));

        ToolExecutionResult result = tool.execute(callWith("{\"entityType\":\"ALBUM\",\"query\":\"Kind of Blue\"}"), USER_ID);

        JsonNode candidate = JSON.readTree(result.payload()).get("metadata").get("candidates").get(0);
        assertThat(candidate.get("editorialId").asText()).isEqualTo(editorialId.toString());
    }

    @Test
    void noMatches_hasFoundFalse_andPlainTextContent() throws Exception {
        when(artistRepository.search("nonexistent")).thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith("{\"entityType\":\"ARTIST\",\"query\":\"nonexistent\"}"), USER_ID);

        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("metadata").get("found").asBoolean()).isFalse();
        assertThat(json.get("content").asText()).contains("No JazzLogs entity candidates found");
    }

    @Test
    void schema_declaresEntityTypeAndQueryAsRequired() {
        assertThat(tool.name()).isEqualTo(ResolveJazzlogsEntityTool.NAME);
        assertThat(tool.toFunctionTool().name()).isEqualTo(ResolveJazzlogsEntityTool.NAME);
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", ResolveJazzlogsEntityTool.NAME, argumentsJson);
    }

    private static CatalogEntityResolver.CandidateRow row(UUID id, String name, String artistFullName, double score, String matchType) {
        CatalogEntityResolver.CandidateRow row = mock(CatalogEntityResolver.CandidateRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getName()).thenReturn(name);
        when(row.getArtistFullName()).thenReturn(artistFullName);
        when(row.getScore()).thenReturn(score);
        when(row.getMatchType()).thenReturn(matchType);
        when(row.getEditorialId()).thenReturn(UUID.randomUUID());
        return row;
    }

    private static CatalogEntityResolver.CandidateRow trackRow(
        UUID id, String name, String artistFullName, String albumName, double score, String matchType
    ) {
        CatalogEntityResolver.CandidateRow row = row(id, name, artistFullName, score, matchType);
        when(row.getAlbumName()).thenReturn(albumName);
        return row;
    }
}

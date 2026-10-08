package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.graph.VocabularyDimension;
import com.jazzlogs.backend.tracksearch.TrackCandidate;
import com.jazzlogs.backend.tracksearch.TrackCandidate.Passage;
import com.jazzlogs.backend.tracksearch.TrackSearchCriteria;
import com.jazzlogs.backend.tracksearch.TrackSearchService;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;

// What matches and in what order is TrackSearchServiceTest's job — here the
// service is a mock, and these cover the tool's own part: turning the model's
// JSON into criteria, rejecting what isn't valid, and the shape handed back.
@ExtendWith(MockitoExtension.class)
class FindTracksToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private TrackSearchService trackSearchService;

    private FindTracksTool tool;

    @BeforeEach
    void setUp() {
        tool = new FindTracksTool(trackSearchService, new JsonMapper());
    }

    @Test
    void passesEverythingTheModelAskedFor_andWhoItIsFor() {
        UUID albumId = UUID.randomUUID();
        String mood = MoodVocabulary.values()[0].name();
        String instrument = InstrumentVocabulary.values()[0].name();
        when(trackSearchService.search(any(), any())).thenReturn(List.of());

        tool.execute(callWith("""
            {"lookingFor":"late and quiet","moods":["%s"],"instruments":["%s"],"albumId":"%s"}
            """.formatted(mood, instrument, albumId)), USER_ID);

        ArgumentCaptor<TrackSearchCriteria> criteria = ArgumentCaptor.forClass(TrackSearchCriteria.class);
        verify(trackSearchService).search(criteria.capture(), org.mockito.ArgumentMatchers.eq(USER_ID));
        assertThat(criteria.getValue().lookingFor()).isEqualTo("late and quiet");
        assertThat(criteria.getValue().moods()).containsExactly(MoodVocabulary.values()[0]);
        assertThat(criteria.getValue().instruments()).containsExactly(InstrumentVocabulary.values()[0]);
        assertThat(criteria.getValue().styles()).isEmpty();
        assertThat(criteria.getValue().albumId()).isEqualTo(albumId);
        assertThat(criteria.getValue().artistId()).isNull();
    }

    @Test
    void codeOutsideTheVocabulary_throws() {
        ToolCallRequest call = callWith("{\"moods\":[\"NOT_A_REAL_MOOD\"]}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedScopeId_throws() {
        ToolCallRequest call = callWith("{\"albumId\":\"not-a-uuid\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("albumId");
    }

    @Test
    void returnsEachCandidateWithWhyItMatched() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackSearchService.search(any(), any())).thenReturn(List.of(new TrackCandidate(
            trackId, "Blue in Green", "Miles Davis", "Kind of Blue", JazzlogsCharacter.LAURA,
            Map.of(VocabularyDimension.MOOD, List.of("RELAXED")),
            new Passage(BlockContentCategory.MOOD_AND_ATMOSPHERE, "It barely moves.", 0.71),
            true
        )));

        ToolExecutionResult result = tool.execute(callWith("{\"lookingFor\":\"late and quiet\"}"), USER_ID);

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("Found 1 track(s)");
        JsonNode candidate = json.get("metadata").get("candidates").get(0);
        assertThat(candidate.get("entityId").asText()).isEqualTo(trackId.toString());
        assertThat(candidate.get("entityName").asText()).isEqualTo("Blue in Green");
        assertThat(candidate.get("artistFullName").asText()).isEqualTo("Miles Davis");
        assertThat(candidate.get("album").asText()).isEqualTo("Kind of Blue");
        assertThat(candidate.get("writtenBy").asText()).isEqualTo("LAURA");
        assertThat(candidate.get("matchedTags").get("MOOD").get(0).asText()).isEqualTo("RELAXED");
        assertThat(candidate.get("closestPassage").get("text").asText()).isEqualTo("It barely moves.");
        assertThat(candidate.get("closestPassage").get("similarity").asDouble()).isEqualTo(0.71);
        assertThat(candidate.get("alreadyListened").asBoolean()).isTrue();
    }

    @Test
    void noCandidates_saysWhatToTryNext() throws Exception {
        when(trackSearchService.search(any(), any())).thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith("{\"lookingFor\":\"late and quiet\"}"), USER_ID);

        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("No tracks matched", "Try");
        assertThat(json.get("metadata").get("candidates")).isEmpty();
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", FindTracksTool.NAME, argumentsJson);
    }
}

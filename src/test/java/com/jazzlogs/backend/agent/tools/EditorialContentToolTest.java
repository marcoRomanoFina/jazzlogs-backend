package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.agent.tools.TrackLogReader.Block;
import com.jazzlogs.backend.agent.tools.TrackLogReader.Log;
import com.jazzlogs.backend.agent.tools.TrackLogReader.TrackInfo;
import com.jazzlogs.backend.agent.tools.TrackLogReader.TrackLog;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockType;
import com.jazzlogs.backend.graph.TrackPerformerEntry;

// What the reader loads is TrackLogReaderTest's job — here it is a mock, and
// these cover the tool's own part: validating the id, telling the model when
// it isn't a track, and the shape of what it hands back.
@ExtendWith(MockitoExtension.class)
class EditorialContentToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // EditorialContentTool never reads userId — any fixed value works here.
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private TrackLogReader trackLogReader;

    private EditorialContentTool tool;

    @BeforeEach
    void setUp() {
        tool = new EditorialContentTool(trackLogReader, new JsonMapper());
    }

    @Test
    void blankTrackId_throws() {
        ToolCallRequest call = callWith("{\"trackId\":\"\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedTrackId_throws() {
        ToolCallRequest call = callWith("{\"trackId\":\"not-a-uuid\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idThatIsNotATrack_tellsTheModelWhatToPassInstead() {
        UUID albumId = UUID.randomUUID();
        when(trackLogReader.read(albumId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.execute(callWith("{\"trackId\":\"" + albumId + "\"}"), USER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not a track")
            .hasMessageContaining("album or artist id");
    }

    @Test
    void returnsTheTrackAndItsWholeLog() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackLogReader.read(trackId)).thenReturn(Optional.of(trackLog(trackId)));

        ToolExecutionResult result = tool.execute(callWith("{\"trackId\":\"" + trackId + "\"}"), USER_ID);

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("Log #42", "So What", "LAURA", "2 block(s)");

        JsonNode track = json.get("metadata").get("track");
        assertThat(track.get("entityId").asText()).isEqualTo(trackId.toString());
        assertThat(track.get("entityName").asText()).isEqualTo("So What");
        assertThat(track.get("artists").get(0).asText()).isEqualTo("Miles Davis");
        assertThat(track.get("album").asText()).isEqualTo("Kind of Blue");
        assertThat(track.get("duration").asText()).isEqualTo("9:22");
        assertThat(track.get("moods").get(0).asText()).isEqualTo("RELAXED");
        assertThat(track.get("performers").get(0).get("artistName").asText()).isEqualTo("Bill Evans");
        assertThat(track.get("performers").get(0).get("instruments").get(0).asText()).isEqualTo("PIANO");

        JsonNode log = json.get("metadata").get("log");
        assertThat(log.get("title").asText()).isEqualTo("The Question and the Answer");
        assertThat(log.get("writtenBy").asText()).isEqualTo("LAURA");
        assertThat(log.get("blocks")).hasSize(2);
        assertThat(log.get("blocks").get(1).get("contentCategory").asText()).isEqualTo("CONTEXT");
        assertThat(log.get("blocks").get(1).get("text").asText()).isEqualTo("Recorded in 1959.");
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", EditorialContentTool.NAME, argumentsJson);
    }

    private static TrackLog trackLog(UUID trackId) {
        TrackInfo track = new TrackInfo(
            trackId, "So What", List.of("Miles Davis"), UUID.randomUUID(), "Kind of Blue", 1959, 1, "9:22",
            null, null, null, null, null, null,
            List.of("MODAL_JAZZ"), List.of("RELAXED"), List.of(), List.of(), List.of("TRUMPET"),
            List.of(new TrackPerformerEntry(UUID.randomUUID(), "Bill Evans", "SIDEMAN", List.of("PIANO"), false))
        );
        Log log = new Log(
            "The Question and the Answer", "42", "A dek.", JazzlogsCharacter.LAURA, "2026-09-01",
            List.of(
                new Block(EditorialBlockType.LEAD, BlockContentCategory.HOOK, null, "Two notes."),
                new Block(EditorialBlockType.PARA, BlockContentCategory.CONTEXT, "Where it came from", "Recorded in 1959.")
            )
        );
        return new TrackLog(track, log);
    }
}

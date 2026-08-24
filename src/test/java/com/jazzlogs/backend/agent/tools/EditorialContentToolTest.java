package com.jazzlogs.backend.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlock;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockType;

@ExtendWith(MockitoExtension.class)
class EditorialContentToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // EditorialContentTool never reads userId — any fixed value works here.
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private EditorialBlockRepository editorialBlockRepository;

    private EditorialContentTool tool;

    @BeforeEach
    void setUp() {
        tool = new EditorialContentTool(editorialBlockRepository, new JsonMapper());
    }

    @Test
    void blankEditorialId_throws() {
        ToolCallRequest call = callWith("{\"editorialId\":\"\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedEditorialId_throws() {
        ToolCallRequest call = callWith("{\"editorialId\":\"not-a-uuid\"}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCategory_throws() {
        UUID editorialId = UUID.randomUUID();
        ToolCallRequest call = callWith("{\"editorialId\":\"" + editorialId + "\",\"categories\":[\"NOT_REAL\"]}");

        assertThatThrownBy(() -> tool.execute(call, USER_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noCategories_fetchesAllBlocksOrderedByPosition() throws Exception {
        UUID editorialId = UUID.randomUUID();
        EditorialBlock block = block(0, "text 1", BlockContentCategory.HISTORICAL_CONTEXT);
        when(editorialBlockRepository.findByEditorialIdOrderByPositionAsc(editorialId)).thenReturn(List.of(block));

        ToolExecutionResult result = tool.execute(callWith("{\"editorialId\":\"" + editorialId + "\"}"), USER_ID);

        JsonNode metadata = JSON.readTree(result.payload()).get("metadata");
        assertThat(metadata.get("editorialId").asText()).isEqualTo(editorialId.toString());
        assertThat(metadata.get("blocks")).hasSize(1);
        assertThat(metadata.get("blocks").get(0).get("contentCategory").asText()).isEqualTo("HISTORICAL_CONTEXT");
    }

    @Test
    void withCategories_filtersByContentCategory() throws Exception {
        UUID editorialId = UUID.randomUUID();
        EditorialBlock block = block(0, "a story", BlockContentCategory.ANECDOTE);
        when(editorialBlockRepository.findByEditorialIdAndContentCategoryInOrderByPositionAsc(editorialId, List.of(BlockContentCategory.ANECDOTE)))
            .thenReturn(List.of(block));

        ToolExecutionResult result = tool.execute(callWith(
            "{\"editorialId\":\"" + editorialId + "\",\"categories\":[\"ANECDOTE\"]}"
        ), USER_ID);

        JsonNode metadata = JSON.readTree(result.payload()).get("metadata");
        assertThat(metadata.get("blocks")).hasSize(1);
        verify(editorialBlockRepository).findByEditorialIdAndContentCategoryInOrderByPositionAsc(editorialId, List.of(BlockContentCategory.ANECDOTE));
    }

    @Test
    void noBlocksFound_hasPlainTextContent() throws Exception {
        UUID editorialId = UUID.randomUUID();
        when(editorialBlockRepository.findByEditorialIdOrderByPositionAsc(editorialId)).thenReturn(List.of());

        ToolExecutionResult result = tool.execute(callWith("{\"editorialId\":\"" + editorialId + "\"}"), USER_ID);

        JsonNode json = JSON.readTree(result.payload());
        assertThat(json.get("content").asText()).contains("No blocks found");
        assertThat(json.get("metadata").get("blocks")).isEmpty();
    }

    private static ToolCallRequest callWith(String argumentsJson) {
        return new ToolCallRequest("call_1", EditorialContentTool.NAME, argumentsJson);
    }

    // editorial is null here on purpose: EditorialContentTool never reads
    // block.getEditorial() — the editorialId in its output comes straight
    // from the tool's own input, not from the block.
    private static EditorialBlock block(int position, String text, BlockContentCategory category) {
        EditorialBlock block = new EditorialBlock(null, position, EditorialBlockType.PARA, null, text, category, null, null);
        ReflectionTestUtils.setField(block, "id", UUID.randomUUID());
        return block;
    }
}

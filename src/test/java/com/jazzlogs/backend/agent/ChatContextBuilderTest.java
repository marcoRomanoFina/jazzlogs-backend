package com.jazzlogs.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chatexchange.ChatExchange;
import com.jazzlogs.backend.chat.chatexchange.ChatExchangeRepository;
import com.jazzlogs.backend.chat.chatexchange.ChatRecommendationMemory;
import com.jazzlogs.backend.chat.chatexchange.ChatRecommendationMemoryRepository;
import com.jazzlogs.backend.chat.chatexchange.WinnerRef;
import com.jazzlogs.backend.user.User;

// Pure Mockito unit tests, no Spring context: ChatContextBuilder is a pure
// function of what its two repositories return. Deliberately not a
// @SpringBootTest hitting a real/H2 database (unlike most service tests in
// this codebase) — createdAt is stamped via @PrePersist with Instant.now(),
// so asserting exact ascending order against real persisted rows would be at
// the mercy of clock resolution. Mocking the repositories lets each test hand
// back an exact, deterministic ordering instead.
//
// chat is given a real id in setUp() — every test here models an
// already-persisted chat (continuing a conversation), which is the only case
// where the repositories are consulted at all. The genuinely-new,
// not-yet-persisted case (chat.getId() == null, see ChatService.createChat)
// is its own dedicated test below, since it skips both repositories entirely.
@ExtendWith(MockitoExtension.class)
class ChatContextBuilderTest {

    @Mock
    private ChatExchangeRepository chatExchangeRepository;

    @Mock
    private ChatRecommendationMemoryRepository chatRecommendationMemoryRepository;

    private ChatContextBuilder builder;
    private Chat chat;

    @BeforeEach
    void setUp() {
        builder = new ChatContextBuilder(chatExchangeRepository, chatRecommendationMemoryRepository, new VocabularyProvider());
        User user = new User(UUID.randomUUID(), "test@example.com");
        chat = new Chat(user, null);
        ReflectionTestUtils.setField(chat, "id", UUID.randomUUID());
    }

    @Test
    void existingChatWithoutMemoryOrExchangesYet_usesPlaceholdersAndOnlyTheNewUserMessage() {
        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.empty());

        List<ResponseInputItem> input = builder.buildInput(chat, "What should I listen to tonight?");

        assertThat(input).hasSize(2);
        assertThat(roleOf(input.get(0))).isEqualTo(EasyInputMessage.Role.DEVELOPER);
        String developerText = textOf(input.get(0));
        assertThat(developerText).contains("SESSION SUMMARY\n(none yet — this is the start of the conversation)");
        assertThat(developerText).contains("RECOMMENDATION HISTORY\n(none yet)");

        assertThat(roleOf(input.get(1))).isEqualTo(EasyInputMessage.Role.USER);
        assertThat(textOf(input.get(1))).isEqualTo("What should I listen to tonight?");
    }

    // Covers ChatService.createChat's contract: a brand-new chat is built in
    // memory only, never saved, until ChatExchangeService.persist() gives it
    // its first exchange — so chat.getId() is null the whole time this runs.
    @Test
    void brandNewChatWithNoIdYet_skipsBothRepositoriesEntirely() {
        User user = new User(UUID.randomUUID(), "test@example.com");
        Chat newChat = new Chat(user, null);

        List<ResponseInputItem> input = builder.buildInput(newChat, "What should I listen to tonight?");

        assertThat(input).hasSize(2);
        String developerText = textOf(input.get(0));
        assertThat(developerText).contains("SESSION SUMMARY\n(none yet — this is the start of the conversation)");
        assertThat(developerText).contains("RECOMMENDATION HISTORY\n(none yet)");
        assertThat(developerText).contains("Chat session id: (new chat, not yet created)");
        assertThat(textOf(input.get(1))).isEqualTo("What should I listen to tonight?");

        verify(chatExchangeRepository, never()).findTop3ByChatIdOrderByCreatedAtDesc(any());
        verify(chatRecommendationMemoryRepository, never()).findByChatId(any());
    }

    @Test
    void chatWithMemoryButNoRecentExchanges_includesSummaryAndHistoryWithNoConversationTurns() {
        ChatRecommendationMemory memory = new ChatRecommendationMemory(chat.getId());
        memory.updateSessionSummary("User is into late-night piano trios.");
        memory.appendWinners(List.of(new WinnerRef(CatalogItemType.ALBUM, UUID.randomUUID(), "Waltz for Debby", "Bill Evans")), 100);

        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.of(memory));

        List<ResponseInputItem> input = builder.buildInput(chat, "Anything similar?");

        assertThat(input).hasSize(2); // developer + final user message, no history turns
        String developerText = textOf(input.get(0));
        assertThat(developerText).contains("SESSION SUMMARY\nUser is into late-night piano trios.");
        assertThat(developerText).contains(
            "RECOMMENDATION HISTORY\nAlready recommended in this session, avoid repeating unless the user asks again:\n"
                + "- \"Waltz for Debby\" — Bill Evans"
        );
    }

    @Test
    void oneRecentExchange_isIncludedAsAConversationTurn() {
        ChatExchange exchange = new ChatExchange(chat, "Recommend something mellow", "How about Kind of Blue?", null);
        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of(exchange));
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.empty());

        List<ResponseInputItem> input = builder.buildInput(chat, "What else?");

        assertThat(input).hasSize(4); // developer, user_1, assistant_1, user_actual
        assertThat(roleOf(input.get(1))).isEqualTo(EasyInputMessage.Role.USER);
        assertThat(textOf(input.get(1))).isEqualTo("Recommend something mellow");
        assertThat(roleOf(input.get(2))).isEqualTo(EasyInputMessage.Role.ASSISTANT);
        assertThat(textOf(input.get(2))).isEqualTo("How about Kind of Blue?");
        assertThat(textOf(input.get(3))).isEqualTo("What else?");
    }

    @Test
    void twoRecentExchanges_areReversedToAscendingOrder() {
        ChatExchange first = new ChatExchange(chat, "msg1", "resp1", null);
        ChatExchange second = new ChatExchange(chat, "msg2", "resp2", null);
        // Repository contract is newest-first — hand back [second, first].
        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of(second, first));
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.empty());

        List<ResponseInputItem> input = builder.buildInput(chat, "final message");

        assertThat(input).hasSize(6); // developer, 2 pairs, user_actual
        assertThat(textOf(input.get(1))).isEqualTo("msg1");
        assertThat(textOf(input.get(2))).isEqualTo("resp1");
        assertThat(textOf(input.get(3))).isEqualTo("msg2");
        assertThat(textOf(input.get(4))).isEqualTo("resp2");
        assertThat(textOf(input.get(5))).isEqualTo("final message");
    }

    @Test
    void threeRecentExchanges_areReversedToAscendingOrder() {
        ChatExchange first = new ChatExchange(chat, "msg1", "resp1", null);
        ChatExchange second = new ChatExchange(chat, "msg2", "resp2", null);
        ChatExchange third = new ChatExchange(chat, "msg3", "resp3", null);
        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of(third, second, first));
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.empty());

        List<ResponseInputItem> input = builder.buildInput(chat, "final message");

        assertThat(input).hasSize(8); // developer, 3 pairs, user_actual
        assertThat(textOf(input.get(1))).isEqualTo("msg1");
        assertThat(textOf(input.get(2))).isEqualTo("resp1");
        assertThat(textOf(input.get(3))).isEqualTo("msg2");
        assertThat(textOf(input.get(4))).isEqualTo("resp2");
        assertThat(textOf(input.get(5))).isEqualTo("msg3");
        assertThat(textOf(input.get(6))).isEqualTo("resp3");
        assertThat(textOf(input.get(7))).isEqualTo("final message");
    }

    @Test
    void recommendationHistoryItemWithNullPrimaryArtist_isFormattedWithoutArtistSeparator() {
        ChatRecommendationMemory memory = new ChatRecommendationMemory(chat.getId());
        memory.appendWinners(List.of(new WinnerRef(CatalogItemType.ARTIST, UUID.randomUUID(), "Bill Evans", null)), 100);

        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.of(memory));

        String developerText = textOf(builder.buildInput(chat, "hi").get(0));

        assertThat(developerText).contains("- \"Bill Evans\"");
        assertThat(developerText).doesNotContain("\"Bill Evans\" —");
    }

    private static String textOf(ResponseInputItem item) {
        return item.easyInputMessage().orElseThrow().content().asTextInput();
    }

    private static EasyInputMessage.Role roleOf(ResponseInputItem item) {
        return item.easyInputMessage().orElseThrow().role();
    }
}

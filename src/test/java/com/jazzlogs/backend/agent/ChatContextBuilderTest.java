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
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chatexchange.ChatExchange;
import com.jazzlogs.backend.chat.chatexchange.ChatExchangeRepository;
import com.jazzlogs.backend.chat.chatexchange.ChatRecommendationMemory;
import com.jazzlogs.backend.chat.chatexchange.ChatRecommendationMemoryRepository;
import com.jazzlogs.backend.chat.chatexchange.WinnerReference;
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
        builder = new ChatContextBuilder(
            chatExchangeRepository,
            chatRecommendationMemoryRepository,
            new VocabularyProvider(),
            new NarratorPersonas(new DefaultResourceLoader(), "classpath:agent/narrators-fixture/")
        );
        User user = new User(UUID.randomUUID(), "test@example.com");
        chat = new Chat(user, null, JazzlogsCharacter.MARK);
        ReflectionTestUtils.setField(chat, "id", UUID.randomUUID());
    }

    @Test
    void existingChatWithoutMemoryOrExchangesYet_usesPlaceholdersAndOnlyTheNewUserMessage() {
        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.empty());

        List<ResponseInputItem> input = builder.buildInput(chat, "What should I listen to tonight?", null);

        assertThat(input).hasSize(2);
        assertThat(roleOf(input.get(0))).isEqualTo(EasyInputMessage.Role.DEVELOPER);
        String developerText = textOf(input.get(0));
        assertThat(developerText).contains("SESSION SUMMARY\n(none yet — this is the start of the conversation)");
        assertThat(developerText).contains("RECOMMENDATION HISTORY\n(none yet)");

        assertThat(roleOf(input.get(1))).isEqualTo(EasyInputMessage.Role.USER);
        assertThat(textOf(input.get(1))).isEqualTo("What should I listen to tonight?");
    }

    @Test
    void developerMessage_speaksAsTheChatsOwnNarrator_notAnyOther() {
        User user = new User(UUID.randomUUID(), "test@example.com");
        Chat laurasChat = new Chat(user, null, JazzlogsCharacter.LAURA);

        String developerText = textOf(builder.buildInput(laurasChat, "Hi", null).get(0));

        assertThat(developerText).contains("You are Laura, one of the eight JazzLogs narrators");
        assertThat(developerText).contains("Fixture traits for Laura.");
        assertThat(developerText).doesNotContain("You are Mark,");
    }

    /** Prompt caching keys on the unchanged prefix — see the ordering comment in buildInput. */
    @Test
    void developerMessage_putsEverythingSharedAcrossChatsBeforeAnythingThatVaries() {
        User user = new User(UUID.randomUUID(), "test@example.com");
        String marks = textOf(builder.buildInput(new Chat(user, null, JazzlogsCharacter.MARK), "Hi", "UTC").get(0));
        String lauras = textOf(builder.buildInput(new Chat(user, null, JazzlogsCharacter.LAURA), "Hola", "America/Argentina/Buenos_Aires").get(0));

        int narratorStartsAt = marks.indexOf("YOUR CHARACTER\nYou are Mark");
        String sharedPrefix = marks.substring(0, narratorStartsAt);

        assertThat(sharedPrefix).contains("FINAL OUTPUT CONTRACT").contains("CANONICAL FILTER VOCABULARY");
        assertThat(lauras).startsWith(sharedPrefix);
        assertThat(marks.indexOf("RUNTIME CONTEXT")).isGreaterThan(marks.indexOf("ON OTHER JAZZLOGS FRIENDS"));
    }

    @Test
    void runtimeContext_givesTheLocalMomentWithItsWeekday_andNeverTheZoneItself() {
        String developerText = textOf(builder.buildInput(chat, "Hi", "America/Argentina/Buenos_Aires").get(0));

        assertThat(developerText).containsPattern("Current local date and time for the user: [A-Z][a-z]+day \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\n");
        assertThat(developerText).doesNotContain("Buenos_Aires").doesNotContain("User timezone");
    }

    @Test
    void runtimeContext_saysTheTimeIsUnknown_ratherThanShowingAnotherZonesClock() {
        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.empty());

        assertThat(textOf(builder.buildInput(chat, "Hi", null).get(0))).contains("Current local date and time for the user: unknown");
        assertThat(textOf(builder.buildInput(chat, "Hi", "Not/AZone").get(0))).contains("Current local date and time for the user: unknown");
    }

    // Covers ChatService.createChat's contract: a brand-new chat is built in
    // memory only, never saved, until ChatExchangeService.persist() gives it
    // its first exchange — so chat.getId() is null the whole time this runs.
    @Test
    void brandNewChatWithNoIdYet_skipsBothRepositoriesEntirely() {
        User user = new User(UUID.randomUUID(), "test@example.com");
        Chat newChat = new Chat(user, null, JazzlogsCharacter.MARK);

        List<ResponseInputItem> input = builder.buildInput(newChat, "What should I listen to tonight?", null);

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
        memory.appendWinners(List.of(new WinnerReference(CatalogItemType.ALBUM, UUID.randomUUID(), "Waltz for Debby", "Bill Evans")));

        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.of(memory));

        List<ResponseInputItem> input = builder.buildInput(chat, "Anything similar?", null);

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

        List<ResponseInputItem> input = builder.buildInput(chat, "What else?", null);

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

        List<ResponseInputItem> input = builder.buildInput(chat, "final message", null);

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

        List<ResponseInputItem> input = builder.buildInput(chat, "final message", null);

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
        memory.appendWinners(List.of(new WinnerReference(CatalogItemType.ARTIST, UUID.randomUUID(), "Bill Evans", null)));

        when(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())).thenReturn(List.of());
        when(chatRecommendationMemoryRepository.findByChatId(chat.getId())).thenReturn(Optional.of(memory));

        String developerText = textOf(builder.buildInput(chat, "hi", null).get(0));

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

package com.jazzlogs.backend.chat.chatexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chat.ChatRepository;
import com.jazzlogs.backend.chat.chat.ChatService;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;
import com.jazzlogs.backend.user.User;

// The one place the model's final-answer ids are checked against reality —
// JazzlogsAgent just forwards whatever the model said (see
// JazzlogsAgentTest, which mocks this class entirely). These tests are the
// real coverage for "a partially hallucinated id gets dropped, but a
// totally hallucinated set rejects the turn" and "DIRECT_RESPONSE has null
// winners, not an empty list". A winner is returned as its log's catalogue
// card, looked up through EditorialService (a mock here — building the card
// is EditorialService's job). TRACK is the only recommendable type — a stray
// ALBUM/ARTIST ref (model schema no longer produces one, but old persisted
// rows could still carry one) simply never resolves, same as any other
// hallucinated/stale id.
@ExtendWith(MockitoExtension.class)
class ChatExchangeServiceTest {

    @Mock
    private ChatService chatService;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatExchangeRepository chatExchangeRepository;

    @Mock
    private ChatRecommendationMemoryService chatRecommendationMemoryService;

    @Mock
    private EditorialService editorialService;

    private ChatExchangeService service;
    private Chat chat;
    private UUID chatOwnerId;

    @BeforeEach
    void setUp() {
        service = new ChatExchangeService(
            chatService, chatRepository, chatExchangeRepository, chatRecommendationMemoryService, editorialService
        );

        // The user's own id is assigned on save, which never happens here — set it by hand.
        chatOwnerId = UUID.randomUUID();
        User owner = new User(UUID.randomUUID(), "test@example.com");
        ReflectionTestUtils.setField(owner, "id", chatOwnerId);
        chat = new Chat(owner, null, JazzlogsCharacter.MARK);
    }

    // Only the persist() tests below need chatExchangeRepository.save
    // stubbed — pulling this into setUp() would make it an unused stubbing
    // (and fail Mockito's strict-stubs check) for the getChatExchanges tests
    // further down, which never call save.
    private void stubSaveAssignsIdAndCreatedAt() {
        when(chatExchangeRepository.save(any())).thenAnswer(invocation -> {
            ChatExchange saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(saved, "createdAt", Instant.now());
            return saved;
        });
    }

    @Test
    void resolvesRealTrackId_toItsLogsCatalogueCard_andDropsHallucinatedOne() {
        stubSaveAssignsIdAndCreatedAt();
        UUID trackId = UUID.randomUUID();
        TrackEditorialCatalogueDto card = card(trackId, "Acknowledgement", "John Coltrane", "A Love Supreme");
        when(editorialService.getCatalogueCardsByTrackId(List.of(trackId), chatOwnerId)).thenReturn(Map.of(trackId, card));

        ChatExchangeDto result = service.persist(
            chat, "recommend something mellow", "here you go",
            List.of(
                new CatalogReference(CatalogItemType.TRACK, trackId.toString()),
                new CatalogReference(CatalogItemType.TRACK, "hallucinated-id-the-model-made-up")
            ),
            null, null
        );

        assertThat(result.winners()).containsExactly(card);
    }

    @Test
    void persistsEachWinnerAsALightweightReference_builtFromItsCard() {
        stubSaveAssignsIdAndCreatedAt();
        UUID trackId = UUID.randomUUID();
        when(editorialService.getCatalogueCardsByTrackId(anyCollection(), any()))
            .thenReturn(Map.of(trackId, card(trackId, "Acknowledgement", "John Coltrane", "A Love Supreme")));

        service.persist(
            chat, "recommend something mellow", "here you go",
            List.of(new CatalogReference(CatalogItemType.TRACK, trackId.toString())), null, null
        );

        ArgumentCaptor<ChatExchange> saved = ArgumentCaptor.forClass(ChatExchange.class);
        verify(chatExchangeRepository).save(saved.capture());
        assertThat(saved.getValue().getWinners())
            .containsExactly(new WinnerReference(CatalogItemType.TRACK, trackId, "Acknowledgement", "John Coltrane"));
    }

    @Test
    void keepsTheModelsOrder_notTheCatalogs() {
        stubSaveAssignsIdAndCreatedAt();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(editorialService.getCatalogueCardsByTrackId(anyCollection(), any())).thenReturn(Map.of(
            second, card(second, "Second Pick", "Someone", "An Album"),
            first, card(first, "First Pick", "Someone", "An Album")
        ));

        ChatExchangeDto result = service.persist(
            chat, "two please", "here you go",
            List.of(new CatalogReference(CatalogItemType.TRACK, first.toString()), new CatalogReference(CatalogItemType.TRACK, second.toString())),
            null, null
        );

        assertThat(result.winners()).extracting(TrackEditorialCatalogueDto::trackName).containsExactly("First Pick", "Second Pick");
    }

    @Test
    void aStrayAlbumTypedReference_neverResolves_trackIsTheOnlyRecommendableType() {
        stubSaveAssignsIdAndCreatedAt();
        UUID trackId = UUID.randomUUID();
        UUID albumId = UUID.randomUUID();
        // Only the track's id is ever looked up — the album-typed ref never reaches the catalog.
        when(editorialService.getCatalogueCardsByTrackId(List.of(trackId), chatOwnerId))
            .thenReturn(Map.of(trackId, card(trackId, "So What", "Miles Davis", "Kind of Blue")));

        ChatExchangeDto result = service.persist(
            chat, "recommend something mellow", "here you go",
            List.of(
                new CatalogReference(CatalogItemType.TRACK, trackId.toString()),
                new CatalogReference(CatalogItemType.ALBUM, albumId.toString())
            ),
            null, null
        );

        assertThat(result.winners()).extracting(TrackEditorialCatalogueDto::trackId).containsExactly(trackId);
    }

    @Test
    void nullRecommendedItems_hasNullWinners_notAnEmptyList_andNeverQueriesTheCatalog() {
        stubSaveAssignsIdAndCreatedAt();
        ChatExchangeDto result = service.persist(chat, "hi", "hey there", null, null, null);

        assertThat(result.winners()).isNull();
        verifyNoInteractions(editorialService, chatRecommendationMemoryService);
    }

    @Test
    void allIdsHallucinated_rejectsTheWholeTurn_ratherThanPersistingEmptyWinners() {
        assertThatThrownBy(() -> service.persist(
            chat, "recommend something", "here you go",
            List.of(new CatalogReference(CatalogItemType.TRACK, "not-a-uuid")),
            null, null
        )).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(chatExchangeRepository, chatRecommendationMemoryService);
    }

    // --- getChatExchanges ---
    //
    // Coverage for the read side of the flow: ownership is delegated to
    // ChatService (never re-checked here), and winners are re-resolved fresh
    // against the catalog from the persisted WinnerReference snapshot — never
    // trusted as-is, same "don't trust what's already stored" posture as
    // resolveWinners takes with the model's raw ids above.

    @Test
    void getChatExchanges_delegatesOwnershipCheck_andResolvesCardFreshFromTheCatalog() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(chat, "id", chatId);
        Pageable pageable = PageRequest.of(0, 10);
        UUID trackId = UUID.randomUUID();

        // Deliberately stale — a fresh lookup must win over this snapshot.
        WinnerReference staleRef = new WinnerReference(CatalogItemType.TRACK, trackId, "So What", "Miles Davis");
        ChatExchange exchange = newExchange(chat, "recommend something mellow", "here you go", List.of(staleRef));

        when(chatService.getOwnedChat(chatId, userId)).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));
        // Likes are the requesting user's, so the lookup is made on their behalf.
        when(editorialService.getCatalogueCardsByTrackId(List.of(trackId), userId))
            .thenReturn(Map.of(trackId, card(trackId, "So What (Remastered)", "Miles Davis", "Kind of Blue (Remastered)")));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, userId, pageable);

        assertThat(result.getContent()).hasSize(1);
        ChatExchangeDto dto = result.getContent().get(0);
        assertThat(dto.chatId()).isEqualTo(chatId);
        assertThat(dto.userMessage()).isEqualTo("recommend something mellow");
        assertThat(dto.finalResponse()).isEqualTo("here you go");
        assertThat(dto.winners()).hasSize(1);
        assertThat(dto.winners().get(0).trackId()).isEqualTo(trackId);
        // Re-resolved name, not the stale WinnerReference.name snapshot.
        assertThat(dto.winners().get(0).trackName()).isEqualTo("So What (Remastered)");
    }

    @Test
    void getChatExchanges_batchesCardLookupOncePerPage_notOncePerExchange() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);
        UUID firstTrackId = UUID.randomUUID();
        UUID secondTrackId = UUID.randomUUID();

        ChatExchange firstExchange = newExchange(
            chat, "first", "first reply", List.of(new WinnerReference(CatalogItemType.TRACK, firstTrackId, "My Foolish Heart", "Bill Evans"))
        );
        ChatExchange secondExchange = newExchange(
            chat, "second", "second reply", List.of(new WinnerReference(CatalogItemType.TRACK, secondTrackId, "Waltz for Debby", "Bill Evans"))
        );

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(firstExchange, secondExchange)));
        when(editorialService.getCatalogueCardsByTrackId(anyCollection(), any())).thenReturn(Map.of(
            firstTrackId, card(firstTrackId, "My Foolish Heart", "Bill Evans", "Waltz for Debby"),
            secondTrackId, card(secondTrackId, "Waltz for Debby", "Bill Evans", "Waltz for Debby")
        ));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).allSatisfy(dto -> assertThat(dto.winners()).hasSize(1));
        // One batched call for the whole page's tracks, not one per exchange.
        verify(editorialService, times(1)).getCatalogueCardsByTrackId(anyCollection(), any());
    }

    @Test
    void getChatExchanges_winnerRefToADeletedEntity_isDroppedWithoutThrowing() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        WinnerReference refToDeletedTrack = new WinnerReference(CatalogItemType.TRACK, UUID.randomUUID(), "Some Track", "Some Artist");
        ChatExchange exchange = newExchange(chat, "recommend something", "here you go", List.of(refToDeletedTrack));

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));
        // The track was deleted since — no card comes back for its id.
        when(editorialService.getCatalogueCardsByTrackId(anyCollection(), any())).thenReturn(Map.of());

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).winners()).isEmpty();
    }

    @Test
    void getChatExchanges_historicalAlbumTypedWinner_isDroppedWithoutThrowing() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        // From before TRACK became the only recommendable type — an album has
        // no log to make a card from, so this must drop silently, not error.
        WinnerReference historicalAlbumWinner = new WinnerReference(CatalogItemType.ALBUM, UUID.randomUUID(), "Some Album", "Some Artist");
        ChatExchange exchange = newExchange(chat, "recommend something", "here you go", List.of(historicalAlbumWinner));

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).winners()).isEmpty();
        // The album's id is never looked up as if it were a track's.
        verify(editorialService).getCatalogueCardsByTrackId(eq(List.of()), any());
    }

    @Test
    void getChatExchanges_exchangeWithNoWinners_hasNullWinners_andAsksTheCatalogForNothing() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        ChatExchange exchange = newExchange(chat, "hi", "hey there", null);

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).winners()).isNull();
        // An empty lookup, which EditorialService answers without a query.
        verify(editorialService).getCatalogueCardsByTrackId(eq(List.of()), any());
    }

    private static TrackEditorialCatalogueDto card(UUID trackId, String trackName, String artistName, String albumName) {
        return new TrackEditorialCatalogueDto(
            UUID.randomUUID(), trackId, trackName, 562_000, "https://open.spotify.com/track/x", null, albumName, UUID.randomUUID(), artistName,
            trackName + " — the log", "12", "A dek.", JazzlogsCharacter.LAURA, Instant.now(), 3, false
        );
    }

    // Builds an already-persisted ChatExchange — id/createdAt are normally
    // stamped by @GeneratedValue/@PrePersist inside a real persistence
    // context, which findByChatId's mocked return value never goes through.
    private static ChatExchange newExchange(Chat chat, String userMessage, String finalResponse, List<WinnerReference> winners) {
        ChatExchange exchange = new ChatExchange(chat, userMessage, finalResponse, winners);
        ReflectionTestUtils.setField(exchange, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(exchange, "createdAt", Instant.now());
        return exchange;
    }
}

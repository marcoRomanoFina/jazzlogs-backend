package com.jazzlogs.backend.chat.chatexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chat.ChatRepository;
import com.jazzlogs.backend.chat.chat.ChatService;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.chatexchange.dto.TrackWinnerCard;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;

// The one place the model's final-answer ids are checked against reality —
// JazzlogsAgent just forwards whatever the model said (see
// JazzlogsAgentTest, which mocks this class entirely). These tests are the
// real coverage for "a partially hallucinated id gets dropped, but a
// totally hallucinated set rejects the turn" and "DIRECT_RESPONSE has null
// winners, not an empty list". TRACK is the only recommendable type — a
// stray ALBUM/ARTIST ref (model schema no longer produces one, but old
// persisted rows could still carry one) simply never resolves, same as any
// other hallucinated/stale id.
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
    private TrackRepository trackRepository;

    private ChatExchangeService service;
    private Chat chat;

    @BeforeEach
    void setUp() {
        service = new ChatExchangeService(
            chatService, chatRepository, chatExchangeRepository, chatRecommendationMemoryService, trackRepository
        );

        User user = new User(UUID.randomUUID(), "test@example.com");
        chat = new Chat(user, null);
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
    void resolvesRealTrackId_andDropsHallucinatedOne() {
        stubSaveAssignsIdAndCreatedAt();
        Artist artist = new Artist("John Coltrane", null, null, null);
        Album album = new Album(artist, "A Love Supreme", null, null, null, 1965, 4);
        Track track = new Track(album, null, "Acknowledgement", null, null, null, null, null, null, null, null, null);
        UUID trackId = UUID.randomUUID();
        ReflectionTestUtils.setField(track, "id", trackId);

        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of(track));

        ChatExchangeDto result = service.persist(
            chat, "recommend something mellow", "here you go",
            List.of(
                new CatalogReference(CatalogItemType.TRACK, trackId.toString()),
                new CatalogReference(CatalogItemType.TRACK, "hallucinated-id-the-model-made-up")
            ),
            null, null
        );

        assertThat(result.winners()).hasSize(1);
        assertThat(result.winners().get(0)).isInstanceOf(TrackWinnerCard.class);
        TrackWinnerCard card = (TrackWinnerCard) result.winners().get(0);
        assertThat(card.id()).isEqualTo(trackId);
        assertThat(card.name()).isEqualTo("Acknowledgement");
        assertThat(card.primaryArtist()).isEqualTo("John Coltrane");
        assertThat(card.albumName()).isEqualTo("A Love Supreme");
    }

    @Test
    void aStrayAlbumTypedReference_neverResolves_trackIsTheOnlyRecommendableType() {
        stubSaveAssignsIdAndCreatedAt();
        Artist artist = new Artist("Miles Davis", null, null, null);
        Album album = new Album(artist, "Kind of Blue", null, null, null, 1959, 5);
        Track track = new Track(album, null, "So What", null, null, null, null, null, null, null, null, null);
        UUID trackId = UUID.randomUUID();
        UUID albumId = UUID.randomUUID();
        ReflectionTestUtils.setField(track, "id", trackId);

        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of(track));

        ChatExchangeDto result = service.persist(
            chat, "recommend something mellow", "here you go",
            List.of(
                new CatalogReference(CatalogItemType.TRACK, trackId.toString()),
                new CatalogReference(CatalogItemType.ALBUM, albumId.toString())
            ),
            null, null
        );

        assertThat(result.winners()).hasSize(1);
        assertThat(result.winners().get(0)).isInstanceOf(TrackWinnerCard.class);
    }

    @Test
    void nullRecommendedItems_hasNullWinners_notAnEmptyList_andNeverQueriesTheCatalog() {
        stubSaveAssignsIdAndCreatedAt();
        ChatExchangeDto result = service.persist(chat, "hi", "hey there", null, null, null);

        assertThat(result.winners()).isNull();
        verify(trackRepository, never()).findAllByIdWithAlbumAndArtist(any());
        verify(chatRecommendationMemoryService, never()).syncMemoryUpdate(any(), any(), any());
    }

    @Test
    void allIdsHallucinated_rejectsTheWholeTurn_ratherThanPersistingEmptyWinners() {
        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of());

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

        Artist artist = new Artist("Miles Davis", null, null, null);
        Album album = new Album(artist, "Kind of Blue (Remastered)", null, null, null, 1959, 5);
        Track track = new Track(album, null, "So What (Remastered)", null, null, null, null, null, null, null, null, null);
        UUID trackId = UUID.randomUUID();
        ReflectionTestUtils.setField(track, "id", trackId);

        // Deliberately stale — a fresh lookup must win over this snapshot.
        WinnerReference staleRef = new WinnerReference(CatalogItemType.TRACK, trackId, "So What", "Miles Davis");
        ChatExchange exchange = newExchange(chat, "recommend something mellow", "here you go", List.of(staleRef));

        when(chatService.getOwnedChat(chatId, userId)).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));
        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of(track));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, userId, pageable);

        assertThat(result.getContent()).hasSize(1);
        ChatExchangeDto dto = result.getContent().get(0);
        assertThat(dto.chatId()).isEqualTo(chatId);
        assertThat(dto.userMessage()).isEqualTo("recommend something mellow");
        assertThat(dto.finalResponse()).isEqualTo("here you go");
        assertThat(dto.winners()).hasSize(1);
        TrackWinnerCard card = (TrackWinnerCard) dto.winners().get(0);
        assertThat(card.id()).isEqualTo(trackId);
        // Re-resolved name, not the stale WinnerReference.name snapshot.
        assertThat(card.name()).isEqualTo("So What (Remastered)");
    }

    @Test
    void getChatExchanges_batchesCardLookupOncePerPage_notOncePerExchange() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        Artist artist = new Artist("Bill Evans", null, null, null);
        Album album = new Album(artist, "Waltz for Debby", null, null, null, 1961, 5);
        Track firstTrack = new Track(album, null, "My Foolish Heart", null, null, null, null, null, null, null, null, null);
        Track secondTrack = new Track(album, null, "Waltz for Debby", null, null, null, null, null, null, null, null, null);
        UUID firstTrackId = UUID.randomUUID();
        UUID secondTrackId = UUID.randomUUID();
        ReflectionTestUtils.setField(firstTrack, "id", firstTrackId);
        ReflectionTestUtils.setField(secondTrack, "id", secondTrackId);

        ChatExchange firstExchange = newExchange(
            chat, "first", "first reply", List.of(new WinnerReference(CatalogItemType.TRACK, firstTrackId, "My Foolish Heart", "Bill Evans"))
        );
        ChatExchange secondExchange = newExchange(
            chat, "second", "second reply", List.of(new WinnerReference(CatalogItemType.TRACK, secondTrackId, "Waltz for Debby", "Bill Evans"))
        );

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(firstExchange, secondExchange)));
        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of(firstTrack, secondTrack));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).allSatisfy(dto -> assertThat(dto.winners()).hasSize(1));
        // One batched call for the whole page's tracks, not one per exchange.
        verify(trackRepository, times(1)).findAllByIdWithAlbumAndArtist(anyList());
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
        // The track was deleted since — no row comes back for its id.
        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of());

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).winners()).isEmpty();
    }

    @Test
    void getChatExchanges_historicalAlbumTypedWinner_isDroppedWithoutThrowing() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        // From before TRACK became the only recommendable type — no card
        // builder exists for ALBUM anymore, so this must drop silently, not error.
        WinnerReference historicalAlbumWinner = new WinnerReference(CatalogItemType.ALBUM, UUID.randomUUID(), "Some Album", "Some Artist");
        ChatExchange exchange = newExchange(chat, "recommend something", "here you go", List.of(historicalAlbumWinner));

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).winners()).isEmpty();
    }

    @Test
    void getChatExchanges_exchangeWithNoWinners_hasNullWinners_andNeverQueriesTheCatalog() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        ChatExchange exchange = newExchange(chat, "hi", "hey there", null);

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).winners()).isNull();
        verify(trackRepository, never()).findAllByIdWithAlbumAndArtist(any());
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

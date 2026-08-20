package com.jazzlogs.backend.chat.chatexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chat.ChatRepository;
import com.jazzlogs.backend.chat.chat.ChatService;
import com.jazzlogs.backend.chat.chatexchange.dto.AlbumWinnerCard;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.chatexchange.dto.TrackWinnerCard;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;

// The one place submit_final_answer's ids are checked against reality —
// AgentOrchestrator just forwards whatever the model said (see
// AgentOrchestratorTest, which mocks this class entirely). These tests are
// the real coverage for "a hallucinated/malformed id gets dropped" and
// "DIRECT_RESPONSE has null winners, not an empty list".
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
    private AlbumRepository albumRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private ArtistRepository artistRepository;

    private ChatExchangeService service;
    private Chat chat;

    @BeforeEach
    void setUp() {
        service = new ChatExchangeService(
            chatService, chatRepository, chatExchangeRepository, chatRecommendationMemoryService,
            albumRepository, trackRepository, artistRepository
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
    void resolvesRealAlbumId_andDropsHallucinatedOne() {
        stubSaveAssignsIdAndCreatedAt();
        Artist artist = new Artist("Miles Davis", null, null, null);
        Album album = new Album(artist, "Kind of Blue", null, null, null, 1959, 5, null, null, null, null, null, null, null, null);
        UUID albumId = UUID.randomUUID();
        ReflectionTestUtils.setField(album, "id", albumId);

        when(albumRepository.findAllByIdWithArtist(anyList())).thenReturn(List.of(album));

        ChatExchangeDto result = service.persist(
            chat, "recommend something mellow", "here you go",
            List.of(
                new CatalogRef(CatalogItemType.ALBUM, albumId.toString()),
                new CatalogRef(CatalogItemType.ALBUM, "hallucinated-id-the-model-made-up")
            ),
            null, null
        );

        assertThat(result.winners()).hasSize(1);
        assertThat(result.winners().get(0)).isInstanceOf(AlbumWinnerCard.class);
        AlbumWinnerCard card = (AlbumWinnerCard) result.winners().get(0);
        assertThat(card.id()).isEqualTo(albumId);
        assertThat(card.name()).isEqualTo("Kind of Blue");
        assertThat(card.primaryArtist()).isEqualTo("Miles Davis");
    }

    @Test
    void resolvesRealTrackId_primaryArtistComesFromItsAlbum() {
        stubSaveAssignsIdAndCreatedAt();
        Artist artist = new Artist("John Coltrane", null, null, null);
        Album album = new Album(artist, "A Love Supreme", null, null, null, 1965, 4, null, null, null, null, null, null, null, null);
        Track track = new Track(album, null, "Acknowledgement", null, null, null, false, null, null, null, null, null, null);
        UUID trackId = UUID.randomUUID();
        ReflectionTestUtils.setField(track, "id", trackId);

        when(trackRepository.findAllByIdWithAlbumAndArtist(anyList())).thenReturn(List.of(track));

        ChatExchangeDto result = service.persist(
            chat, "recommend a track", "here you go",
            List.of(new CatalogRef(CatalogItemType.TRACK, trackId.toString())),
            null, null
        );

        assertThat(result.winners()).hasSize(1);
        assertThat(result.winners().get(0)).isInstanceOf(TrackWinnerCard.class);
        assertThat(((TrackWinnerCard) result.winners().get(0)).primaryArtist()).isEqualTo("John Coltrane");
    }

    @Test
    void nullRecommendedItems_hasNullWinners_notAnEmptyList_andNeverQueriesTheCatalog() {
        stubSaveAssignsIdAndCreatedAt();
        ChatExchangeDto result = service.persist(chat, "hi", "hey there", null, null, null);

        assertThat(result.winners()).isNull();
        verify(albumRepository, never()).findAllByIdWithArtist(any());
        verify(trackRepository, never()).findAllByIdWithAlbumAndArtist(any());
        verify(artistRepository, never()).findAllById(any());
        verify(chatRecommendationMemoryService, never()).syncMemoryUpdate(any(), any(), any());
    }

    @Test
    void malformedId_isDroppedWithoutThrowing() {
        stubSaveAssignsIdAndCreatedAt();
        when(albumRepository.findAllByIdWithArtist(anyList())).thenReturn(List.of());

        ChatExchangeDto result = service.persist(
            chat, "recommend something", "here you go",
            List.of(new CatalogRef(CatalogItemType.ALBUM, "not-a-uuid")),
            null, null
        );

        assertThat(result.winners()).isEmpty();
    }

    // --- getChatExchanges ---
    //
    // Coverage for the read side of the flow: ownership is delegated to
    // ChatService (never re-checked here), and winners are re-resolved fresh
    // against the catalog from the persisted WinnerRef snapshot — never
    // trusted as-is, same "don't trust what's already stored" posture as
    // resolveWinners takes with the model's raw ids above.

    @Test
    void getChatExchanges_delegatesOwnershipCheck_andResolvesCardFreshFromTheCatalog() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(chat, "id", chatId);
        Pageable pageable = PageRequest.of(0, 10);

        Artist artist = new Artist("Miles Davis", null, null, null);
        Album album = new Album(artist, "Kind of Blue (Remastered)", null, null, null, 1959, 5, null, null, null, null, null, null, null, null);
        UUID albumId = UUID.randomUUID();
        ReflectionTestUtils.setField(album, "id", albumId);

        // Deliberately stale — a fresh lookup must win over this snapshot.
        WinnerRef staleRef = new WinnerRef(CatalogItemType.ALBUM, albumId, "Kind of Blue", "Miles Davis");
        ChatExchange exchange = newExchange(chat, "recommend something mellow", "here you go", List.of(staleRef));

        when(chatService.getOwnedChat(chatId, userId)).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));
        when(albumRepository.findAllByIdWithArtist(anyList())).thenReturn(List.of(album));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, userId, pageable);

        assertThat(result.getContent()).hasSize(1);
        ChatExchangeDto dto = result.getContent().get(0);
        assertThat(dto.chatId()).isEqualTo(chatId);
        assertThat(dto.userMessage()).isEqualTo("recommend something mellow");
        assertThat(dto.finalResponse()).isEqualTo("here you go");
        assertThat(dto.winners()).hasSize(1);
        AlbumWinnerCard card = (AlbumWinnerCard) dto.winners().get(0);
        assertThat(card.id()).isEqualTo(albumId);
        // Re-resolved name, not the stale WinnerRef.name snapshot.
        assertThat(card.name()).isEqualTo("Kind of Blue (Remastered)");
    }

    @Test
    void getChatExchanges_batchesCardLookupOncePerPage_notOncePerExchange() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        Artist artist = new Artist("Bill Evans", null, null, null);
        Album firstAlbum = new Album(artist, "Waltz for Debby", null, null, null, 1961, 5, null, null, null, null, null, null, null, null);
        Album secondAlbum = new Album(artist, "Sunday at the Village Vanguard", null, null, null, 1961, 6, null, null, null, null, null, null, null, null);
        UUID firstAlbumId = UUID.randomUUID();
        UUID secondAlbumId = UUID.randomUUID();
        ReflectionTestUtils.setField(firstAlbum, "id", firstAlbumId);
        ReflectionTestUtils.setField(secondAlbum, "id", secondAlbumId);

        ChatExchange firstExchange = newExchange(
            chat, "first", "first reply", List.of(new WinnerRef(CatalogItemType.ALBUM, firstAlbumId, "Waltz for Debby", "Bill Evans"))
        );
        ChatExchange secondExchange = newExchange(
            chat, "second", "second reply", List.of(new WinnerRef(CatalogItemType.ALBUM, secondAlbumId, "Sunday at the Village Vanguard", "Bill Evans"))
        );

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(firstExchange, secondExchange)));
        when(albumRepository.findAllByIdWithArtist(anyList())).thenReturn(List.of(firstAlbum, secondAlbum));

        Page<ChatExchangeDto> result = service.getChatExchanges(chatId, UUID.randomUUID(), pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).allSatisfy(dto -> assertThat(dto.winners()).hasSize(1));
        // One batched call for the whole page's albums, not one per exchange.
        verify(albumRepository, times(1)).findAllByIdWithArtist(anyList());
    }

    @Test
    void getChatExchanges_winnerRefToADeletedEntity_isDroppedWithoutThrowing() {
        UUID chatId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        ReflectionTestUtils.setField(chat, "id", chatId);

        WinnerRef refToDeletedAlbum = new WinnerRef(CatalogItemType.ALBUM, UUID.randomUUID(), "Some Album", "Some Artist");
        ChatExchange exchange = newExchange(chat, "recommend something", "here you go", List.of(refToDeletedAlbum));

        when(chatService.getOwnedChat(any(), any())).thenReturn(chat);
        when(chatExchangeRepository.findByChatId(chatId, pageable)).thenReturn(new PageImpl<>(List.of(exchange)));
        // The album was deleted since — no row comes back for its id.
        when(albumRepository.findAllByIdWithArtist(anyList())).thenReturn(List.of());

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
        verify(albumRepository, never()).findAllByIdWithArtist(any());
        verify(trackRepository, never()).findAllByIdWithAlbumAndArtist(any());
        verify(artistRepository, never()).findAllById(any());
    }

    // Builds an already-persisted ChatExchange — id/createdAt are normally
    // stamped by @GeneratedValue/@PrePersist inside a real persistence
    // context, which findByChatId's mocked return value never goes through.
    private static ChatExchange newExchange(Chat chat, String userMessage, String finalResponse, List<WinnerRef> winners) {
        ChatExchange exchange = new ChatExchange(chat, userMessage, finalResponse, winners);
        ReflectionTestUtils.setField(exchange, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(exchange, "createdAt", Instant.now());
        return exchange;
    }
}

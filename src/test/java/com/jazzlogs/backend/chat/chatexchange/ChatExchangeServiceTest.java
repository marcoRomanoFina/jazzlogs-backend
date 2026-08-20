package com.jazzlogs.backend.chat.chatexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
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

        when(chatExchangeRepository.save(any())).thenAnswer(invocation -> {
            ChatExchange saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(saved, "createdAt", Instant.now());
            return saved;
        });
    }

    @Test
    void resolvesRealAlbumId_andDropsHallucinatedOne() {
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
        ChatExchangeDto result = service.persist(chat, "hi", "hey there", null, null, null);

        assertThat(result.winners()).isNull();
        verify(albumRepository, never()).findAllByIdWithArtist(any());
        verify(trackRepository, never()).findAllByIdWithAlbumAndArtist(any());
        verify(artistRepository, never()).findAllById(any());
        verify(chatRecommendationMemoryService, never()).syncMemoryUpdate(any(), any(), any());
    }

    @Test
    void malformedId_isDroppedWithoutThrowing() {
        when(albumRepository.findAllByIdWithArtist(anyList())).thenReturn(List.of());

        ChatExchangeDto result = service.persist(
            chat, "recommend something", "here you go",
            List.of(new CatalogRef(CatalogItemType.ALBUM, "not-a-uuid")),
            null, null
        );

        assertThat(result.winners()).isEmpty();
    }
}

package com.jazzlogs.backend.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.chat.dto.AlbumWinnerCard;
import com.jazzlogs.backend.chat.dto.ArtistWinnerCard;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.dto.TrackWinnerCard;
import com.jazzlogs.backend.chat.dto.WinnerCard;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

// The only place a chat_exchange ever gets created — called once by
// AgentOrchestrator right after the model closes a turn with
// submit_final_answer. There's no other writer, but reads (getChatExchanges,
// for GET /chats/{chatId}/exchanges) live here too — exchanges are always a
// ChatExchangeRepository concern, whether being written or listed.
@Service
@AllArgsConstructor
public class ChatExchangeService {

    private final ChatService chatService;
    private final ChatRepository chatRepository;
    private final ChatExchangeRepository chatExchangeRepository;
    private final ChatRecommendationMemoryService chatRecommendationMemoryService;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final ArtistRepository artistRepository;

    @Transactional
    public ChatExchangeDto persist(
        Chat chat, String userMessage, String assistantText,
        List<CatalogRef> recommendedItems, String suggestedChatTitle, String updatedSessionSummary
    ) {
        List<ResolvedWinner> resolvedWinners = resolveWinners(recommendedItems);
        List<WinnerRef> winners = toRefs(resolvedWinners);

        ChatExchange saved = chatExchangeRepository.save(new ChatExchange(chat, userMessage, assistantText, winners));

        chat.recordExchangeAt(saved.getCreatedAt());
        // First suggestion wins — a chat gets titled once, early on; later
        // exchanges suggesting a new title don't churn an already-set one.
        if (chat.getTitle() == null && suggestedChatTitle != null && !suggestedChatTitle.isBlank()) {
            chat.updateTitle(suggestedChatTitle);
        }
        chatRepository.save(chat);

        boolean hasWinners = winners != null && !winners.isEmpty();
        boolean hasSummary = updatedSessionSummary != null && !updatedSessionSummary.isBlank();
        if (hasWinners || hasSummary) {
            chatRecommendationMemoryService.syncMemoryUpdate(chat.getId(), winners, updatedSessionSummary);
        }

        return toChatExchangeDto(saved, toCards(resolvedWinners));
    }

    /**
     * Lists the exchanges of a chat the requesting user owns, most recent
     * first by default.
     * <p>
     * Paginated — sort direction/field can be overridden by the client via
     * {@link Pageable}, though the Frontend currently relies on the default
     * ({@code createdAt} DESC).
     *
     * @param chatId           the chat whose exchanges are being listed
     * @param requestingUserId the caller — must own the chat
     * @param pageable         page/size/sort requested by the client
     * @return a page of the chat's exchanges
     */
    @Transactional(readOnly = true)
    public Page<ChatExchangeDto> getChatExchanges(UUID chatId, UUID requestingUserId, Pageable pageable) {
        Chat chat = chatService.getOwnedChat(chatId, requestingUserId);
        Page<ChatExchange> page = chatExchangeRepository.findByChatId(chat.getId(), pageable);

        // Persisted exchanges only carry WinnerRef (id + a name snapshot) —
        // re-resolve the whole page's worth of winners against the current
        // catalog in one batch per type, instead of one query per exchange.
        Map<UUID, WinnerCard> cardsById = resolveWinnerCards(page.getContent());
        return page.map(exchange -> toChatExchangeDto(exchange, toCards(exchange.getWinners(), cardsById)));
    }

    private ChatExchangeDto toChatExchangeDto(ChatExchange exchange, List<WinnerCard> winners) {
        return new ChatExchangeDto(
            exchange.getId(),
            exchange.getChatId(),
            exchange.getUserMessage(),
            exchange.getFinalResponse(),
            winners,
            exchange.getCreatedAt()
        );
    }

    // Bundles a persisted-shape WinnerRef with its display-ready WinnerCard —
    // both built from the exact same Album/Track/Artist row, resolved once.
    private record ResolvedWinner(WinnerRef ref, WinnerCard card) {
    }

    // The only place the model's ids are checked against reality: refs is
    // whatever submit_final_answer echoed back, still possibly hallucinated
    // (a made-up id, a malformed UUID, a stale id from an unrelated turn).
    // Anything that doesn't resolve to a real row is silently dropped, never
    // surfaced as an error. null (not empty) means "don't touch the catalog
    // at all" — AgentOrchestrator passes null exactly when the model's
    // resultType was DIRECT_RESPONSE.
    private List<ResolvedWinner> resolveWinners(List<CatalogRef> refs) {
        if (refs == null) {
            return null;
        }
        if (refs.isEmpty()) {
            return List.of();
        }

        // toWinnerRef/toWinnerCard below reach through a lazy artist
        // association (Track also through a lazy album first) — the plain
        // findAllById(...) these used to call would trigger up to 2 extra
        // per-row SELECTs per item instead of one batched query per type.
        // See AlbumRepository.findAllByIdWithArtist/
        // TrackRepository.findAllByIdWithAlbumAndArtist.
        Map<UUID, ResolvedWinner> resolved = new HashMap<>();
        albumRepository.findAllByIdWithArtist(idsOfType(refs, CatalogItemType.ALBUM))
            .forEach(album -> resolved.put(album.getId(), new ResolvedWinner(toWinnerRef(album), toWinnerCard(album))));
        trackRepository.findAllByIdWithAlbumAndArtist(idsOfType(refs, CatalogItemType.TRACK))
            .forEach(track -> resolved.put(track.getId(), new ResolvedWinner(toWinnerRef(track), toWinnerCard(track))));
        artistRepository.findAllById(idsOfType(refs, CatalogItemType.ARTIST))
            .forEach(artist -> resolved.put(artist.getId(), new ResolvedWinner(toWinnerRef(artist), toWinnerCard(artist))));

        return refs.stream()
            .map(ref -> parseUuid(ref.id()))
            .filter(Objects::nonNull)
            .map(resolved::get)
            .filter(Objects::nonNull)
            .toList();
    }

    // Same batching as resolveWinners, but starting from already-persisted
    // WinnerRefs (a whole page's worth, across however many exchanges) instead
    // of the model's raw CatalogRefs — no id parsing, no "hallucinated id"
    // case, those were already validated once at persist time. A ref whose
    // entity was deleted since simply has no entry here and gets dropped by
    // toCards below, same "degrade gracefully on a stale pointer" call as
    // SavedItemService.toSummary makes for saved items.
    private Map<UUID, WinnerCard> resolveWinnerCards(List<ChatExchange> exchanges) {
        List<WinnerRef> allWinners = exchanges.stream()
            .flatMap(exchange -> exchange.getWinners() == null ? Stream.empty() : exchange.getWinners().stream())
            .toList();
        if (allWinners.isEmpty()) {
            return Map.of();
        }

        Map<UUID, WinnerCard> cards = new HashMap<>();
        albumRepository.findAllByIdWithArtist(winnerIdsOfType(allWinners, CatalogItemType.ALBUM))
            .forEach(album -> cards.put(album.getId(), toWinnerCard(album)));
        trackRepository.findAllByIdWithAlbumAndArtist(winnerIdsOfType(allWinners, CatalogItemType.TRACK))
            .forEach(track -> cards.put(track.getId(), toWinnerCard(track)));
        artistRepository.findAllById(winnerIdsOfType(allWinners, CatalogItemType.ARTIST))
            .forEach(artist -> cards.put(artist.getId(), toWinnerCard(artist)));
        return cards;
    }

    private static List<WinnerRef> toRefs(List<ResolvedWinner> resolvedWinners) {
        return resolvedWinners == null ? null : resolvedWinners.stream().map(ResolvedWinner::ref).toList();
    }

    private static List<WinnerCard> toCards(List<ResolvedWinner> resolvedWinners) {
        return resolvedWinners == null ? null : resolvedWinners.stream().map(ResolvedWinner::card).toList();
    }

    private static List<WinnerCard> toCards(List<WinnerRef> winners, Map<UUID, WinnerCard> cardsById) {
        if (winners == null) {
            return null;
        }
        return winners.stream().map(ref -> cardsById.get(ref.id())).filter(Objects::nonNull).toList();
    }

    private static List<UUID> idsOfType(List<CatalogRef> refs, CatalogItemType type) {
        return refs.stream()
            .filter(ref -> ref.type() == type)
            .map(ref -> parseUuid(ref.id()))
            .filter(Objects::nonNull)
            .toList();
    }

    private static List<UUID> winnerIdsOfType(List<WinnerRef> winners, CatalogItemType type) {
        return winners.stream()
            .filter(winner -> winner.type() == type)
            .map(WinnerRef::id)
            .toList();
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static WinnerRef toWinnerRef(Album album) {
        return new WinnerRef(CatalogItemType.ALBUM, album.getId(), album.getName(), album.getArtist().getName());
    }

    private static WinnerRef toWinnerRef(Track track) {
        return new WinnerRef(CatalogItemType.TRACK, track.getId(), track.getName(), track.getAlbum().getArtist().getName());
    }

    private static WinnerRef toWinnerRef(Artist artist) {
        return new WinnerRef(CatalogItemType.ARTIST, artist.getId(), artist.getName(), null);
    }

    private static WinnerCard toWinnerCard(Album album) {
        return new AlbumWinnerCard(
            album.getId(), album.getName(), album.getImageUrl(),
            album.getArtist().getName(), album.getReleaseYear(), album.getSpotifyUrl()
        );
    }

    private static WinnerCard toWinnerCard(Track track) {
        return new TrackWinnerCard(
            track.getId(), track.getName(), track.getImageUrl(), track.getAlbum().getArtist().getName(),
            track.getAlbum().getName(), track.getDurationMs(), track.getSpotifyUrl()
        );
    }

    private static WinnerCard toWinnerCard(Artist artist) {
        return new ArtistWinnerCard(artist.getId(), artist.getName(), artist.getImageUrl(), artist.getSpotifyUrl());
    }
}

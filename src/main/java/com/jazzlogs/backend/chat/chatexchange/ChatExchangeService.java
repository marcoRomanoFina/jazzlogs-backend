package com.jazzlogs.backend.chat.chatexchange;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chat.ChatRepository;
import com.jazzlogs.backend.chat.chat.ChatService;
import com.jazzlogs.backend.chat.chatexchange.dto.AlbumWinnerCard;
import com.jazzlogs.backend.chat.chatexchange.dto.ArtistWinnerCard;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.chatexchange.dto.TrackWinnerCard;
import com.jazzlogs.backend.chat.chatexchange.dto.WinnerCard;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

/**
 * The only place a {@code chat_exchange} gets created — called once by
 * {@code JazzlogsAgent} after the model closes a turn. Also the only place a
 * brand-new chat's row gets created (see {@code ChatService.createChat},
 * which deliberately never saves) — a chat and its first exchange are born
 * atomically, in the same transaction.
 */
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

    /**
     * Persists one exchange: resolves the model's raw catalog references
     * against the real catalog, saves the chat (creating it, if brand-new)
     * and the exchange, then fires the recommendation-memory update.
     *
     * @param chat                  the chat this exchange belongs to
     * @param userMessage           the user's message this turn
     * @param assistantText         the model's conversational reply
     * @param recommendedItems      the model's raw catalog references; null for a DIRECT_RESPONSE
     * @param suggestedChatTitle    the model's proposed title; applied only if the chat has none yet
     * @param updatedSessionSummary the model's updated session summary
     * @return the persisted exchange, with winners resolved to display-ready cards
     */
    @Transactional
    public ChatExchangeDto persist(
        Chat chat, String userMessage, String assistantText,
        List<CatalogReference> recommendedItems, String suggestedChatTitle, String updatedSessionSummary
    ) {
        Optional<List<ResolvedWinner>> resolvedWinners = resolveWinners(recommendedItems);
        Optional<List<WinnerReference>> winners = toRefs(resolvedWinners);

        // First suggestion wins — never overwrites an already-titled chat.
        if (chat.getTitle() == null && suggestedChatTitle != null && !suggestedChatTitle.isBlank()) {
            chat.updateTitle(suggestedChatTitle);
        }

        // Runs here, inside this same @Transactional method, rather than in
        // ChatService.createChat (which deliberately never saves) for two
        // reasons: a brand-new chat needs its id assigned before the
        // ChatExchange below, which references it via a NOT NULL FK; and it
        // shares the exact transaction as that exchange, so if anything
        // after this fails, both roll back together — no orphaned chat.
        chatRepository.save(chat);

        ChatExchange saved = chatExchangeRepository.save(new ChatExchange(chat, userMessage, assistantText, winners.orElse(null)));
        chat.recordExchangeAt(saved.getCreatedAt());
        chatRepository.save(chat);

        boolean hasWinners = winners.map(w -> !w.isEmpty()).orElse(false);
        boolean hasSummary = updatedSessionSummary != null && !updatedSessionSummary.isBlank();
        if (hasWinners || hasSummary) {
            chatRecommendationMemoryService.syncMemoryUpdate(chat.getId(), winners.orElse(null), updatedSessionSummary);
        }

        return toChatExchangeDto(saved, toCards(resolvedWinners).orElse(null));
    }

    /**
     * Bundles a persisted-shape WinnerReference with its display-ready WinnerCard —
     * both built from the exact same Album/Track/Artist row, resolved once.
     *
     * @param ref  the persisted shape, saved onto the ChatExchange
     * @param card the display-ready shape, returned to the caller
     */
    private record ResolvedWinner(WinnerReference ref, WinnerCard card) {
    }

    /**
     * The only place the model's ids are checked against reality: refs is
     * whatever the model's final answer echoed back, still possibly
     * hallucinated (a made-up id, a malformed UUID, a stale id from an
     * unrelated turn). A ref that doesn't resolve is silently dropped — but
     * if refs was non-empty and NONE of them resolved, the whole turn is
     * rejected instead: an answer that talks about specific recommendations
     * while returning zero of them is worse than a clean failure.
     *
     * @param refs the model's raw catalog references; null means "don't
     *             touch the catalog at all" — JazzlogsAgent passes null
     *             exactly when the model's resultType was DIRECT_RESPONSE
     * @return the refs that resolved to a real row, each paired with its
     *         display-ready card; absent iff refs was null
     * @throws IllegalStateException if refs was non-empty and none resolved
     */
    private Optional<List<ResolvedWinner>> resolveWinners(List<CatalogReference> refs) {
        if (refs == null) {
            return Optional.empty();
        }
        if (refs.isEmpty()) {
            return Optional.of(List.of());
        }

        // Up to 3 queries — one batched call per entity type, never one per ref.
        Map<UUID, ResolvedWinner> resolved = new HashMap<>();
        albumRepository.findAllByIdWithArtist(idsOfType(refs, CatalogItemType.ALBUM))
            .forEach(album -> resolved.put(album.getId(), new ResolvedWinner(toWinnerReference(album), toWinnerCard(album))));
        trackRepository.findAllByIdWithAlbumAndArtist(idsOfType(refs, CatalogItemType.TRACK))
            .forEach(track -> resolved.put(track.getId(), new ResolvedWinner(toWinnerReference(track), toWinnerCard(track))));
        artistRepository.findAllById(idsOfType(refs, CatalogItemType.ARTIST))
            .forEach(artist -> resolved.put(artist.getId(), new ResolvedWinner(toWinnerReference(artist), toWinnerCard(artist))));

        // First filter invalid ids, then filter invalid references.
        List<ResolvedWinner> result = refs.stream()
            .flatMap(ref -> parseUuid(ref.id()).stream())
            .map(resolved::get)
            .filter(Objects::nonNull)
            .toList();

        if (result.isEmpty()) {
            throw new IllegalStateException("None of the model's " + refs.size() + " recommended ids resolved to a real catalog row");
        }
        return Optional.of(result);
    }

    /**
     * Projects the persisted shape out of each resolved winner, for saving
     * onto the ChatExchange.
     *
     * @param resolvedWinners the winners resolved by resolveWinners
     * @return the WinnerReference of each; absent iff resolvedWinners is absent
     */
    private static Optional<List<WinnerReference>> toRefs(Optional<List<ResolvedWinner>> resolvedWinners) {
        return resolvedWinners.map(winners -> winners.stream().map(ResolvedWinner::ref).toList());
    }

    /**
     * Projects the display-ready shape out of each resolved winner, for
     * persist()'s own return value.
     *
     * @param resolvedWinners the winners resolved by resolveWinners
     * @return the WinnerCard of each; absent iff resolvedWinners is absent
     */
    private static Optional<List<WinnerCard>> toCards(Optional<List<ResolvedWinner>> resolvedWinners) {
        return resolvedWinners.map(winners -> winners.stream().map(ResolvedWinner::card).toList());
    }

    /**
     * Filters the model's raw refs down to one entity type and parses each
     * surviving id, ready to hand to that type's repository.
     *
     * @param refs the model's raw catalog references
     * @param type the entity type to keep
     * @return the parsed ids of only the refs matching {@code type}; a ref
     *         whose id isn't a valid UUID is dropped, not surfaced as an error
     */
    private static List<UUID> idsOfType(List<CatalogReference> refs, CatalogItemType type) {
        return refs.stream()
            .filter(ref -> ref.type() == type)
            .flatMap(ref -> parseUuid(ref.id()).stream())
            .toList();
    }

    /**
     * Best-effort UUID parse for an id the model produced — never trusted to
     * actually be a UUID.
     *
     * @param raw the candidate id, as echoed back by the model
     * @return the parsed UUID, or empty if raw isn't a valid UUID
     */
    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Builds the persisted shape for a resolved album.
     *
     * @param album the resolved catalog row
     * @return the WinnerReference to save onto the ChatExchange
     */
    private static WinnerReference toWinnerReference(Album album) {
        return new WinnerReference(CatalogItemType.ALBUM, album.getId(), album.getName(), album.getArtist().getName());
    }

    /**
     * Builds the persisted shape for a resolved track.
     *
     * @param track the resolved catalog row
     * @return the WinnerReference to save onto the ChatExchange
     */
    private static WinnerReference toWinnerReference(Track track) {
        return new WinnerReference(CatalogItemType.TRACK, track.getId(), track.getName(), track.getAlbum().getArtist().getName());
    }

    /**
     * Builds the persisted shape for a resolved artist.
     *
     * @param artist the resolved catalog row
     * @return the WinnerReference to save onto the ChatExchange
     */
    private static WinnerReference toWinnerReference(Artist artist) {
        return new WinnerReference(CatalogItemType.ARTIST, artist.getId(), artist.getName(), null);
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

        // Persisted exchanges only carry WinnerReference (id + a name snapshot) —
        // re-resolve the whole page's worth of winners against the current
        // catalog in one batch per type, instead of one query per exchange.
        Map<UUID, WinnerCard> cardsById = resolveWinnerCards(page.getContent());
        return page.map(exchange -> toChatExchangeDto(exchange, toCards(exchange.getWinners(), cardsById).orElse(null)));
    }

    /**
     * Same batching as resolveWinners, but starting from already-persisted
     * WinnerReferences.
     *
     * @param exchanges the page's exchanges whose winners need cards
     * @return display-ready cards keyed by entity id; a ref whose entity was
     *         deleted since simply has no entry here and gets dropped by
     *         toCards below
     */
    private Map<UUID, WinnerCard> resolveWinnerCards(List<ChatExchange> exchanges) {
        List<WinnerReference> allWinners = exchanges.stream()
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

    /**
     * Looks up the display-ready card for each already-persisted winner ref.
     *
     * @param winners   an exchange's persisted winner refs
     * @param cardsById cards resolved by resolveWinnerCards, keyed by entity id
     * @return the card for each ref that still resolves; absent iff winners
     *         was null, and a ref with no matching card (its entity was
     *         deleted since) is silently dropped rather than kept as null
     */
    private static Optional<List<WinnerCard>> toCards(List<WinnerReference> winners, Map<UUID, WinnerCard> cardsById) {
        if (winners == null) {
            return Optional.empty();
        }
        return Optional.of(winners.stream().map(ref -> cardsById.get(ref.id())).filter(Objects::nonNull).toList());
    }

    /**
     * Filters a page's worth of persisted winner refs down to one entity
     * type's ids, ready to hand to that type's repository.
     *
     * @param winners the persisted winner refs to filter
     * @param type    the entity type to keep
     * @return the ids of only the refs matching {@code type}
     */
    private static List<UUID> winnerIdsOfType(List<WinnerReference> winners, CatalogItemType type) {
        return winners.stream()
            .filter(winner -> winner.type() == type)
            .map(WinnerReference::id)
            .toList();
    }

    /**
     * Assembles the response shape for one exchange, given its winners
     * already resolved to display-ready cards by whichever flow called this.
     *
     * @param exchange the persisted exchange
     * @param winners  the exchange's winners, already resolved to cards
     * @return the DTO returned to the client
     */
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

    /**
     * Builds the display-ready card for a resolved album.
     *
     * @param album the resolved catalog row
     * @return the card the frontend renders
     */
    private static WinnerCard toWinnerCard(Album album) {
        return new AlbumWinnerCard(
            album.getId(), album.getName(), album.getImageUrl(),
            album.getArtist().getName(), album.getReleaseYear(), album.getSpotifyUrl()
        );
    }

    /**
     * Builds the display-ready card for a resolved track.
     *
     * @param track the resolved catalog row
     * @return the card the frontend renders
     */
    private static WinnerCard toWinnerCard(Track track) {
        return new TrackWinnerCard(
            track.getId(), track.getName(), track.getImageUrl(), track.getAlbum().getArtist().getName(),
            track.getAlbum().getName(), track.getDurationMs(), track.getSpotifyUrl()
        );
    }

    /**
     * Builds the display-ready card for a resolved artist.
     *
     * @param artist the resolved catalog row
     * @return the card the frontend renders
     */
    private static WinnerCard toWinnerCard(Artist artist) {
        return new ArtistWinnerCard(artist.getId(), artist.getName(), artist.getImageUrl(), artist.getSpotifyUrl());
    }
}

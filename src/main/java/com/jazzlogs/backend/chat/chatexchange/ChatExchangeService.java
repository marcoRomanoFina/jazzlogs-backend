package com.jazzlogs.backend.chat.chatexchange;

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

import com.jazzlogs.backend.chat.CatalogItemType;
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chat.ChatRepository;
import com.jazzlogs.backend.chat.chat.ChatService;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;

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
    private final EditorialService editorialService;

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
     * @return the persisted exchange, with its winners as display-ready cards
     */
    @Transactional
    public ChatExchangeDto persist(
        Chat chat, String userMessage, String assistantText,
        List<CatalogReference> recommendedItems, String suggestedChatTitle, String updatedSessionSummary
    ) {
        Optional<List<TrackEditorialCatalogueDto>> winnerCards = resolveWinners(recommendedItems, chat.getUserId());
        Optional<List<WinnerReference>> winners = winnerCards.map(cards -> cards.stream().map(ChatExchangeService::toWinnerReference).toList());

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

        return toChatExchangeDto(saved, winnerCards.orElse(null));
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
     * <p>Resolving means finding the track's log: what a chat recommends is
     * a log, so a winner is returned as that log's catalogue card, the same
     * one the archive lists it with. TRACK is the only recommendable type —
     * the model's JSON schema only ever produces it, and a stray ref of
     * another type simply won't resolve, same as any other hallucinated id.
     *
     * @param refs   the model's raw catalog references; null means "don't
     *               touch the catalog at all" — JazzlogsAgent passes null
     *               exactly when the model's resultType was DIRECT_RESPONSE
     * @param userId whose likes the cards reflect
     * @return the card of each ref that resolved, in the model's order;
     *         absent iff refs was null
     * @throws IllegalStateException if refs was non-empty and none resolved
     */
    private Optional<List<TrackEditorialCatalogueDto>> resolveWinners(List<CatalogReference> refs, UUID userId) {
        if (refs == null) {
            return Optional.empty();
        }
        if (refs.isEmpty()) {
            return Optional.of(List.of());
        }

        List<UUID> trackIds = refs.stream()
            .filter(ref -> ref.type() == CatalogItemType.TRACK)
            .flatMap(ref -> parseUuid(ref.id()).stream())
            .toList();
        Map<UUID, TrackEditorialCatalogueDto> cardsByTrackId = editorialService.getCatalogueCardsByTrackId(trackIds, userId);

        List<TrackEditorialCatalogueDto> cards = trackIds.stream().map(cardsByTrackId::get).filter(Objects::nonNull).toList();
        if (cards.isEmpty()) {
            throw new IllegalStateException("None of the model's " + refs.size() + " recommended ids resolved to a real catalog row");
        }
        return Optional.of(cards);
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

    /** The persisted shape of a winner — just enough to identify it and avoid recommending it twice. */
    private static WinnerReference toWinnerReference(TrackEditorialCatalogueDto card) {
        return new WinnerReference(CatalogItemType.TRACK, card.trackId(), card.trackName(), card.artistName());
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
        // catalog in one batch, instead of one query per exchange.
        Map<UUID, TrackEditorialCatalogueDto> cardsByTrackId = editorialService.getCatalogueCardsByTrackId(
            winnerTrackIds(page.getContent()), requestingUserId
        );
        return page.map(exchange -> toChatExchangeDto(exchange, toCards(exchange.getWinners(), cardsByTrackId)));
    }

    /**
     * Every track a page of exchanges recommended. A historical ALBUM/ARTIST
     * winner, from before TRACK became the only recommendable type, is left
     * out here and so silently drops out of the response instead of erroring.
     */
    private static List<UUID> winnerTrackIds(List<ChatExchange> exchanges) {
        return exchanges.stream()
            .flatMap(exchange -> exchange.getWinners() == null ? Stream.empty() : exchange.getWinners().stream())
            .filter(winner -> winner.type() == CatalogItemType.TRACK)
            .map(WinnerReference::id)
            .distinct()
            .toList();
    }

    /**
     * @return the card of each persisted winner that still resolves, in order;
     *         {@code null} iff the exchange had no winners at all (a
     *         DIRECT_RESPONSE), and a winner whose track or log is gone is
     *         dropped rather than kept as null
     */
    private static List<TrackEditorialCatalogueDto> toCards(List<WinnerReference> winners, Map<UUID, TrackEditorialCatalogueDto> cardsByTrackId) {
        if (winners == null) {
            return null;
        }
        return winners.stream().map(ref -> cardsByTrackId.get(ref.id())).filter(Objects::nonNull).toList();
    }

    /**
     * Assembles the response shape for one exchange, given its winners
     * already resolved to display-ready cards by whichever flow called this.
     */
    private ChatExchangeDto toChatExchangeDto(ChatExchange exchange, List<TrackEditorialCatalogueDto> winners) {
        return new ChatExchangeDto(
            exchange.getId(),
            exchange.getChatId(),
            exchange.getUserMessage(),
            exchange.getFinalResponse(),
            winners,
            exchange.getCreatedAt()
        );
    }
}

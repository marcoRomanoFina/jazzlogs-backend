package com.jazzlogs.backend.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;
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
        List<WinnerRef> winners = resolveWinners(recommendedItems);

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

        return toChatExchangeDto(saved);
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
        return chatExchangeRepository.findByChatId(chat.getId(), pageable).map(this::toChatExchangeDto);
    }

    private ChatExchangeDto toChatExchangeDto(ChatExchange exchange) {
        return new ChatExchangeDto(
            exchange.getId(),
            exchange.getChatId(),
            exchange.getUserMessage(),
            exchange.getFinalResponse(),
            exchange.getWinners(),
            exchange.getCreatedAt()
        );
    }

    // The only place the model's ids are checked against reality: refs is
    // whatever submit_final_answer echoed back, still possibly hallucinated
    // (a made-up id, a malformed UUID, a stale id from an unrelated turn).
    // Anything that doesn't resolve to a real row is silently dropped, never
    // surfaced as an error. null (not empty) means "don't touch the catalog
    // at all" — AgentOrchestrator passes null exactly when the model's
    // resultType was DIRECT_RESPONSE.
    private List<WinnerRef> resolveWinners(List<CatalogRef> refs) {
        if (refs == null) {
            return null;
        }
        if (refs.isEmpty()) {
            return List.of();
        }

        // toWinnerRef(Album)/toWinnerRef(Track) below reach through a lazy
        // artist association (Track also through a lazy album first) — the
        // plain findAllById(...) these used to call would trigger up to 2
        // extra per-row SELECTs per item instead of one batched query per
        // type. See AlbumRepository.findAllByIdWithArtist/
        // TrackRepository.findAllByIdWithAlbumAndArtist.
        Map<UUID, WinnerRef> resolved = new HashMap<>();
        albumRepository.findAllByIdWithArtist(idsOfType(refs, CatalogItemType.ALBUM))
            .forEach(album -> resolved.put(album.getId(), toWinnerRef(album)));
        trackRepository.findAllByIdWithAlbumAndArtist(idsOfType(refs, CatalogItemType.TRACK))
            .forEach(track -> resolved.put(track.getId(), toWinnerRef(track)));
        artistRepository.findAllById(idsOfType(refs, CatalogItemType.ARTIST))
            .forEach(artist -> resolved.put(artist.getId(), toWinnerRef(artist)));

        return refs.stream()
            .map(ref -> parseUuid(ref.id()))
            .filter(Objects::nonNull)
            .map(resolved::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private static List<UUID> idsOfType(List<CatalogRef> refs, CatalogItemType type) {
        return refs.stream()
            .filter(ref -> ref.type() == type)
            .map(ref -> parseUuid(ref.id()))
            .filter(Objects::nonNull)
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
}

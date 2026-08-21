package com.jazzlogs.backend.chat.chat;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.chat.chat.dto.ChatDto;
import com.jazzlogs.backend.user.User;

import lombok.AllArgsConstructor;
/**
 * Service for the {@link Chat} entity itself — listing, creating, and
 * resolving-by-id-with-ownership-check. Depends only on
 * {@link ChatRepository}; the exchanges within a chat are
 * {@code ChatExchangeService}'s responsibility, not this class's.
 */
@Service
@AllArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;

    /**
     * Lists chats belonging to the given user, most recently active first by default.
     * <p>
     * Paginated — sort direction/field can be overridden by the client via
     * {@link Pageable}, though the Frontend currently relies on the default
     * ({@code lastMessageAt} DESC).
     *
     * @param userId   the internal user id
     * @param pageable page/size/sort requested by the client
     * @return a page of the user's chats
     */ 
   @Transactional(readOnly = true)
    public Page<ChatDto> getUserChats(UUID userId, Pageable pageable) {
        return chatRepository.findByUserId(userId, pageable).map(this::toChatDto);
    }

    /**
     * Builds a chat in memory only — never saved here. It's only actually
     * persisted, together with its first exchange, in
     * {@code ChatExchangeService#persist}, so a failed first agent turn
     * leaves nothing behind.
     * 
     * @param user the chat's owner
     * @return an unsaved {@link Chat}
     */
    public Chat createChat(User user) {
        return new Chat(user, null);
    }

    /**
     * Looks up a chat by id and verifies the requesting user owns it.
     *
     * @param chatId           the chat to look up
     * @param requestingUserId the caller — must own the chat
     * @return the chat, with its {@code user} already fetched (not a lazy
     *         proxy), since it's about to be read from the agent's async loop
     * @throws ResponseStatusException 404 if no such chat exists, 403 if it
     *                                  belongs to someone else
     */
    @Transactional(readOnly = true)
    public Chat getOwnedChat(UUID chatId, UUID requestingUserId) {
        Chat chat = chatRepository.findByIdWithUser(chatId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));
        assertOwner(chat, requestingUserId);
        return chat;
    }

    /**
     * Throws 403 if {@code chat} doesn't belong to {@code requestingUserId}.
     *
     * @param chat             the chat to check
     * @param requestingUserId the caller
     */
    private void assertOwner(Chat chat, UUID requestingUserId) {
        if (!chat.getUserId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the chat's owner can access it");
        }
    }

    /**
     * Maps a {@link Chat} entity to its API response shape.
     *
     * @param chat the entity to map
     * @return the corresponding {@link ChatDto}
     */
    private ChatDto toChatDto(Chat chat) {
        return new ChatDto(chat.getId(), chat.getTitle(), chat.getCreatedAt(), chat.getUpdatedAt(), chat.getLastMessageAt());
    }
}

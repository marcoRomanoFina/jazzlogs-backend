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
 * Handles the user´s chats logic
 * <p>
 * provides chat listing, 
 * TODO: expand this Javadoc as each remaining flow gets reviewed
 * (see the "Agent — final review & documentation" milestone).
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
     * <p>
     * Takes the already-resolved {@code user} directly, not just an id —
     * the caller ({@code ChatController#createChat}) already has it from
     * {@code UserService.resolveFromJwt}; re-fetching it here by id would
     * just be a second, redundant read of the same row in the same request.
     *
     * @param user the chat's owner, already resolved
     * @return an unsaved {@link Chat}
     */
    public Chat createChat(User user) {
        return new Chat(user, null);
    }

    // findByIdWithUser (not plain findById) — this Chat is handed to
    // AgentOrchestrator's async loop, which needs chat.getUser() to already
    // be initialized; see ChatRepository.findByIdWithUser.
    @Transactional(readOnly = true)
    public Chat getOwnedChat(UUID chatId, UUID requestingUserId) {
        Chat chat = chatRepository.findByIdWithUser(chatId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));
        assertOwner(chat, requestingUserId);
        return chat;
    }

    private void assertOwner(Chat chat, UUID requestingUserId) {
        if (!chat.getUserId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the chat's owner can access it");
        }
    }

    private ChatDto toChatDto(Chat chat) {
        return new ChatDto(chat.getId(), chat.getTitle(), chat.getCreatedAt(), chat.getUpdatedAt(), chat.getLastMessageAt());
    }
}

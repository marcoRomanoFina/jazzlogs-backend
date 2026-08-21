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
import com.jazzlogs.backend.user.UserRepository;

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
    private final UserRepository userRepository;

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

    // Builds the chat in memory only — never saved here. A chat is only
    // ever born together with its first ChatExchange, inside
    // ChatExchangeService.persist(); if the agent's first turn fails before
    // reaching that point (see AgentOrchestrator.handleFailure), nothing
    // about this chat ever touches the database, instead of leaving behind
    // a title-less, exchange-less row the Frontend can't even navigate to
    // (the new chatId is only ever learned from a successful answer_metadata
    // event). Matches what the Chat class doc already promises: "a chat is
    // created lazily, together with its first ChatExchange".
    //
    // user is a freshly-fetched entity here, not a lazy proxy — safe to read
    // from AgentOrchestrator's async loop with no extra fetch needed.
    @Transactional(readOnly = true)
    public Chat createChat(UUID requestingUserId) {
        User user = getUserOrThrow(requestingUserId);
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

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }
}

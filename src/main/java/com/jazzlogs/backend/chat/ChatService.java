package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.chat.dto.ChatDto;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final ChatExchangeRepository chatExchangeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ChatDto> getUserChats(UUID userId) {
        return chatRepository.findByUserIdOrderByLastMessageAtDesc(userId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ChatExchangeDto> getChatExchanges(UUID chatId, UUID requestingUserId) {
        Chat chat = getChatOrThrow(chatId);
        assertOwner(chat, requestingUserId);
        return chatExchangeRepository.findByChatIdOrderByCreatedAtAsc(chatId).stream().map(this::toDto).toList();
    }

    // user is a freshly-fetched entity here, not a lazy proxy — safe to read
    // from AgentOrchestrator's async loop with no extra fetch needed.
    @Transactional
    public Chat createChat(UUID requestingUserId) {
        User user = getUserOrThrow(requestingUserId);
        return chatRepository.save(new Chat(user, null));
    }

    // findByIdWithUser (not getChatOrThrow/findById) — this Chat is handed to
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

    private ChatDto toDto(Chat chat) {
        return new ChatDto(chat.getId(), chat.getTitle(), chat.getCreatedAt(), chat.getUpdatedAt(), chat.getLastMessageAt());
    }

    private ChatExchangeDto toDto(ChatExchange exchange) {
        return new ChatExchangeDto(
            exchange.getId(),
            exchange.getChatId(),
            exchange.getUserMessage(),
            exchange.getFinalResponse(),
            exchange.getWinners(),
            exchange.getCreatedAt()
        );
    }

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }

    private Chat getChatOrThrow(UUID chatId) {
        return chatRepository.findById(chatId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));
    }
}

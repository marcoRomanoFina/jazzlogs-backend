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
    private final ChatRecommendationMemoryService chatRecommendationMemoryService;

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

   

    @Transactional
    public ChatExchangeDto createChatWithExchange(UUID requestingUserId, String userMessage, String finalResponse, List<WinnerRef> winners) {
        User user = getUserOrThrow(requestingUserId);
        Chat chat = chatRepository.save(new Chat(user, null));
        return persistExchange(chat, userMessage, finalResponse, winners);
    }

    @Transactional
    public ChatExchangeDto createExchange(UUID chatId, UUID requestingUserId, String userMessage, String finalResponse, List<WinnerRef> winners) {
        Chat chat = getChatOrThrow(chatId);
        assertOwner(chat, requestingUserId);
        return persistExchange(chat, userMessage, finalResponse, winners);
    }

    private ChatExchangeDto persistExchange(Chat chat, String userMessage, String finalResponse, List<WinnerRef> winners) {
        ChatExchange saved = chatExchangeRepository.save(new ChatExchange(chat, userMessage, finalResponse, winners));

        chat.recordExchangeAt(saved.getCreatedAt());
        chatRepository.save(chat);

        if (winners != null && !winners.isEmpty()) {
            chatRecommendationMemoryService.syncWinners(chat.getId(), winners);
        }

        return toDto(saved);
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

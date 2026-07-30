package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.jazzlogs.backend.agent.AgentOrchestrator;
import com.jazzlogs.backend.chat.dto.ChatDto;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.dto.SendMessageRequest;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/chats")
@AllArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final UserService userService;
    private final AgentOrchestrator agentOrchestrator;

    @GetMapping
    public List<ChatDto> listChats(@AuthenticationPrincipal Jwt jwt) {
        return chatService.getUserChats(currentUserId(jwt));
    }

    @GetMapping("/{chatId}/exchanges")
    public List<ChatExchangeDto> listExchanges(@PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt) {
        return chatService.getChatExchanges(chatId, currentUserId(jwt));
    }

    // First message of a brand-new chat — creates it, then runs the agent
    // exactly like sendMessage does. There's no separate "just create an
    // empty chat" route: a chat without a first message doesn't mean anything.
    @PostMapping
    public SseEmitter createChat(@Valid @RequestBody SendMessageRequest request, @AuthenticationPrincipal Jwt jwt) {
        Chat chat = chatService.createChat(currentUserId(jwt));
        return agentOrchestrator.runExchange(chat, request.userMessage(), request.timezone());
    }

    @PostMapping("/{chatId}/messages")
    public SseEmitter sendMessage(
        @PathVariable UUID chatId,
        @Valid @RequestBody SendMessageRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        Chat chat = chatService.getOwnedChat(chatId, currentUserId(jwt));
        return agentOrchestrator.runExchange(chat, request.userMessage(), request.timezone());
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}

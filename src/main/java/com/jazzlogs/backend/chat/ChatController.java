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

import com.jazzlogs.backend.chat.dto.ChatDto;
import com.jazzlogs.backend.chat.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.dto.CreateChatExchangeRequest;
import com.jazzlogs.backend.chat.dto.CreateChatWithExchangeRequest;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/chats")
@AllArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final UserService userService;

    @GetMapping
    public List<ChatDto> listChats(@AuthenticationPrincipal Jwt jwt) {
        return chatService.getUserChats(currentUserId(jwt));
    }

    @GetMapping("/{chatId}/exchanges")
    public List<ChatExchangeDto> listExchanges(@PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt) {
        return chatService.getChatExchanges(chatId, currentUserId(jwt));
    }

   
    @PostMapping("/new-with-exchange")
    public ChatExchangeDto createChatWithExchange(@Valid @RequestBody CreateChatWithExchangeRequest request, @AuthenticationPrincipal Jwt jwt) {
        return chatService.createChatWithExchange(currentUserId(jwt), request.userMessage(), request.finalResponse(), request.winners());
    }

    @PostMapping("/{chatId}/exchanges")
    public ChatExchangeDto createExchange(
        @PathVariable UUID chatId,
        @Valid @RequestBody CreateChatExchangeRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return chatService.createExchange(chatId, currentUserId(jwt), request.userMessage(), request.finalResponse(), request.winners());
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}

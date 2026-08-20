package com.jazzlogs.backend.chat;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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


/**
 * REST endpoints for chat CRUD — creation, listing, history and sending messages.
 */
@RestController
@RequestMapping("/chats")
@AllArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatExchangeService chatExchangeService;
    private final UserService userService;
    private final AgentOrchestrator agentOrchestrator;

     /**
     * Lists the authenticated user's chats, paged and ordered by most recent
     * activity by default.
     * @param pageable page number/size, defaulting to 20 per page sorted by
     *                 {@code lastMessageAt} DESC
     * @param jwt the authenticated user's token
     * @return a page of chats belonging to the user
     */
    @GetMapping
    public Page<ChatDto> listChats(
        @PageableDefault(size = 20, sort = "lastMessageAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return chatService.getUserChats(currentUserId(jwt), pageable);
    }
    

    /**
     * Lists a chat's exchanges, paged and ordered most-recent-first by default.
     * @param chatId   the chat whose exchanges are being listed
     * @param pageable page number/size, defaulting to 10 per page sorted by
     *                 {@code createdAt} DESC
     * @param jwt the authenticated user's token
     * @return a page of the chat's exchanges
     */
    @GetMapping("/{chatId}/exchanges")
    public Page<ChatExchangeDto> listExchanges(
        @PathVariable UUID chatId,
        @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return chatExchangeService.getChatExchanges(chatId, currentUserId(jwt), pageable);
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

    /** Resolves the internal domain user id (users.id), not the raw Supabase
     subject — bridges Supabase identity to the app's own user record.**/
    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}

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
import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chat.ChatService;
import com.jazzlogs.backend.chat.chat.dto.ChatDto;
import com.jazzlogs.backend.chat.chatexchange.ChatExchangeService;
import com.jazzlogs.backend.chat.chatexchange.dto.ChatExchangeDto;
import com.jazzlogs.backend.chat.chatexchange.dto.SendMessageRequest;
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

    /**
     * Starts a brand-new chat by sending its first message, streaming the
     * agent's turn back over SSE exactly like {@link #sendMessage}.
     * <p>
     * The chat itself isn't saved by this call — {@link ChatService#createChat}
     * only builds it in memory; it's only actually persisted, together with
     * this first exchange, once the agent's turn succeeds (see
     * {@code ChatExchangeService.persist}), so a failed first turn never
     * leaves an empty, unreachable chat behind. There's no {@code Location}
     * header or JSON body to carry the new chat's id either — the caller
     * only learns it from the {@code answer_metadata} SSE event, once the
     * stream actually completes successfully.
     * 
     * @param request the first message to send — same body {@link #sendMessage}
     *                takes: {@code userMessage} and an optional {@code timezone}
     * @param jwt     the authenticated user's token — the new chat's owner
     * @return an {@link SseEmitter} streaming the agent's progress and final answer
     */
    @PostMapping
    public SseEmitter createChat(@Valid @RequestBody SendMessageRequest request, @AuthenticationPrincipal Jwt jwt) {
        Chat chat = chatService.createChat(userService.resolveFromJwt(jwt));
        return agentOrchestrator.runExchange(chat, request.userMessage(), request.timezone());
    }

    /**
     * Continues an existing chat by sending its next message, streaming the
     * agent's turn back over SSE exactly like {@link #createChat}.
     * <p>
     * {@link ChatService#getOwnedChat} resolves the chat and checks {@code
     * jwt}'s user actually owns it — 404 if no such chat exists, 403 if it
     * belongs to someone else — before any agent work starts.
     *
     * @param chatId  the chat to continue
     * @param request the message to send — {@code userMessage} and an
     *                optional {@code timezone}
     * @param jwt     the authenticated user's token — must own {@code chatId}
     * @return an {@link SseEmitter} streaming the agent's progress and final answer
     */
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

package com.jazzlogs.backend.chat.chatexchange;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for {@link ChatExchange} — a chat's individual turns
 * (user message + agent response).
 */
public interface ChatExchangeRepository extends JpaRepository<ChatExchange, UUID> {

    /**
     * Pages a chat's exchanges. Explicit JPQL, not a derived
     * {@code findByChatId} — sidesteps {@code getChatId()} tripping Spring
     * Data's property-path resolver.
     *
     * @param chatId   the owning chat
     * @param pageable page/size/sort requested by the caller
     * @return a page of the chat's exchanges
     */
    @Query("SELECT ce FROM ChatExchange ce WHERE ce.chat.id = :chatId")
    Page<ChatExchange> findByChatId(@Param("chatId") UUID chatId, Pageable pageable);

    /**
     * The 3 most recent exchanges of a chat, newest first — used by
     * ChatContextBuilder to seed the agent's short-term context.
     *
     * @param chatId the owning chat
     * @return up to 3 exchanges, newest first
     */
    @Query("SELECT ce FROM ChatExchange ce WHERE ce.chat.id = :chatId ORDER BY ce.createdAt DESC LIMIT 3")
    List<ChatExchange> findTop3ByChatIdOrderByCreatedAtDesc(@Param("chatId") UUID chatId);
}

package com.jazzlogs.backend.chat;

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
     * {@code findByChatId} — ChatExchange's convenience {@code getChatId()}
     * trips Spring Data's property-path resolver into treating "chatId" as
     * its own attribute instead of drilling into the {@code chat}
     * association. Ordering comes from the Pageable's Sort (see
     * {@link ChatExchangeService#getChatExchanges}), not hardcoded here.
     *
     * @param chatId   the chat whose exchanges are being listed
     * @param pageable page/size/sort requested by the caller
     * @return a page of the chat's exchanges
     */
    @Query("SELECT ce FROM ChatExchange ce WHERE ce.chat.id = :chatId")
    Page<ChatExchange> findByChatId(@Param("chatId") UUID chatId, Pageable pageable);

    // Newest-first, capped — ChatContextBuilder reverses this to ascending
    // order before turning it into conversation turns. Same chatId
    // ambiguity as above, same fix; LIMIT in HQL is a Hibernate 6+
    // extension (no need for a Pageable parameter just to cap 3 rows).
    @Query("SELECT ce FROM ChatExchange ce WHERE ce.chat.id = :chatId ORDER BY ce.createdAt DESC LIMIT 3")
    List<ChatExchange> findTop3ByChatIdOrderByCreatedAtDesc(@Param("chatId") UUID chatId);
}
